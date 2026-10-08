(ns form13.rdf
  "The lab's Form 13 day file as RDF, with Apache Jena by interop.

  The modelling decision that matters. The lab's property graph is
  (:Manager)-[:OWNS {value, shares, reportCalendarOrQuarter}]->(:Company).
  A triple has a subject, a predicate and an object and nowhere to put a
  value, so the relationship cannot stay an edge: it becomes a node.

      holding:X  a            f13:Holding ;
                 f13:manager  manager:Y ;
                 f13:company  company:Z ;
                 f13:value    465999000.0 ;
                 f13:shares   4602 ;
                 f13:quarter  \"2023-03-31\"^^xsd:date .

  That is the n-ary relation pattern (W3C, 'Defining N-ary Relations on the
  Semantic Web'). The alternative is RDF-star, in RDF 1.2 a triple term with
  a reifier, where the bare triple is itself the subject:

      manager:Y f13:owns company:Z {| f13:value 465999000.0 ; f13:shares 4602 |} .

  `annotated-example` below holds one holding written that way; the load
  here uses the Holding node, which every RDF store understands.

  The bare manager-owns-company triple is also asserted, as a shortcut: it
  is derivable from the Holding nodes and carries none of their properties."
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayInputStream)
           (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (org.apache.jena.datatypes.xsd XSDDatatype)
           (org.apache.jena.query QueryExecutionFactory QuerySolution ResultSet)
           (org.apache.jena.rdf.model Model ModelFactory Property RDFNode Resource Statement)
           (org.apache.jena.riot Lang RDFDataMgr RDFFormat)
           (org.apache.jena.sys JenaSystem)
           (org.apache.jena.vocabulary RDF)))

(set! *warn-on-reflection* true)

;; Jena's classes initialise each other in a circle. Touching XSDDatatype
;; before the system is up fails with a NullPointerException in
;; TypeMapper.registerDatatype (seen with 6.2.0), so bring it up first.
(JenaSystem/init)

(def default-csv-path
  "Relative to this experiment's directory, where the REPL and the gate start."
  "../../data/form13-2023-05-11.csv")

(def default-turtle-path "out/form13-2023-05-11.ttl")

;; Vocabulary. example.org is reserved for exactly this: nothing here
;; resolves, and nothing here is anybody's published ontology.

(def base-iri "http://example.org/form13/")
(def vocabulary-iri (str base-iri "vocab#"))
(def manager-iri-prefix (str base-iri "manager/"))
(def company-iri-prefix (str base-iri "company/"))
(def holding-iri-prefix (str base-iri "holding/"))

(def prefixes
  {"f13" vocabulary-iri
   "manager" manager-iri-prefix
   "company" company-iri-prefix
   "holding" holding-iri-prefix
   "rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   "xsd" "http://www.w3.org/2001/XMLSchema#"})

(def sparql-prologue
  "PREFIX lines for every query, so a query in a comment block can start at
  SELECT."
  (str/join "\n" (map (fn [[prefix iri]] (str "PREFIX " prefix ": <" iri ">"))
                      prefixes)))

(defn- iri-segment
  "A string made safe to be one path segment of an IRI."
  [text]
  (str/replace (URLEncoder/encode ^String text StandardCharsets/UTF_8) "+" "%20"))

(defn manager-iri [manager-name] (str manager-iri-prefix (iri-segment manager-name)))
(defn company-iri [cusip] (str company-iri-prefix (iri-segment cusip)))

(defn holding-iri
  "Keyed on manager, CUSIP and quarter, the same key the lab's MERGE uses for
  OWNS. The same row always mints the same IRI."
  [manager-name cusip quarter]
  (str holding-iri-prefix (iri-segment manager-name) "/" (iri-segment cusip) "/" quarter))

;; The file

(defn read-rows
  "Every row of the CSV as a map keyed by the header's column names. Values
  stay strings; the datatype is chosen when the literal is made."
  ([] (read-rows default-csv-path))
  ([csv-path]
   (with-open [reader (io/reader csv-path)]
     (let [[header & lines] (csv/read-csv reader)
           column-keys (map keyword header)]
       (mapv #(zipmap column-keys %) lines)))))

;; Rows to triples

(defn- vocabulary-term
  "A class or property of the vocabulary, by local name."
  ^Property [^Model model local-name]
  (.createProperty model vocabulary-iri local-name))

(defn- add-triple!
  "Adds one triple. The object is a node, or a string for a plain literal."
  [^Model model ^Resource subject property-name object]
  (let [^RDFNode object-node (if (string? object)
                               (.createLiteral model ^String object)
                               object)]
    (.add model subject (vocabulary-term model property-name) object-node)))

(defn- typed-literal
  ^RDFNode [^Model model ^String lexical-form ^XSDDatatype datatype]
  (.createTypedLiteral model lexical-form datatype))

(defn add-row!
  "Adds one CSV row to the model: a Manager, a Company, the Holding that
  joins them and carries the row's own properties, and the bare owns triple.
  A model is a set of triples, so a manager seen a thousand times is still
  two triples, and a company filed under three names has three companyName
  values and no winner."
  [^Model model {:keys [managerName reportCalendarOrQuarter cusip value shares
                        sourceFilingId companyName]}]
  (let [manager (.createResource model ^String (manager-iri managerName))
        company (.createResource model ^String (company-iri cusip))
        holding (.createResource model ^String (holding-iri managerName cusip
                                                            reportCalendarOrQuarter))
        add-type! (fn [^Resource subject class-name]
                    (.add model subject RDF/type ^RDFNode (vocabulary-term model class-name)))]
    (add-type! manager "Manager")
    (add-triple! model manager "managerName" managerName)
    (add-triple! model manager "owns" company)

    (add-type! company "Company")
    (add-triple! model company "cusip" cusip)
    (add-triple! model company "companyName" companyName)

    (add-type! holding "Holding")
    (add-triple! model holding "manager" manager)
    (add-triple! model holding "company" company)
    (add-triple! model holding "value" (typed-literal model value XSDDatatype/XSDdecimal))
    (add-triple! model holding "shares" (typed-literal model shares XSDDatatype/XSDinteger))
    (add-triple! model holding "quarter"
                 (typed-literal model reportCalendarOrQuarter XSDDatatype/XSDdate))
    (add-triple! model holding "sourceFilingId" sourceFilingId)
    model))

(defn rows->model
  "An in-memory Jena model holding every row."
  ^Model [rows]
  (let [model (ModelFactory/createDefaultModel)]
    (.setNsPrefixes model ^java.util.Map prefixes)
    (run! #(add-row! model %) rows)
    model))

(defn load-model
  "The CSV at csv-path as a model."
  (^Model [] (load-model default-csv-path))
  (^Model [csv-path] (rows->model (read-rows csv-path))))

;; Turtle out, Turtle in

(defn write-turtle!
  "Writes the model as Turtle and returns the path."
  ([model] (write-turtle! model default-turtle-path))
  ([^Model model turtle-path]
   (io/make-parents turtle-path)
   (with-open [output (io/output-stream turtle-path)]
     (RDFDataMgr/write output model RDFFormat/TURTLE_BLOCKS))
   turtle-path))

(defn read-turtle
  "A model from a Turtle file: what any other RDF tool would see."
  ^Model [turtle-path]
  (RDFDataMgr/loadModel turtle-path Lang/TURTLE))

(defn turtle-string->model
  ^Model [^String turtle-text]
  (let [model (ModelFactory/createDefaultModel)]
    (RDFDataMgr/read model
                     (ByteArrayInputStream. (.getBytes turtle-text StandardCharsets/UTF_8))
                     Lang/TURTLE)
    model))

;; Looking at triples

(defn- node->value
  "A Clojure value for a node: an IRI as a string, a literal as its Java
  value (BigDecimal, BigInteger or Long, String), a blank node as its label,
  anything else (a triple term) as Jena prints it."
  [^RDFNode node]
  (cond
    (nil? node) nil
    (.isLiteral node) (let [literal (.asLiteral node)]
                        (try (let [literal-value (.getValue literal)]
                               (if (or (number? literal-value) (string? literal-value))
                                 literal-value
                                 (.getLexicalForm literal)))
                             (catch Exception _ (.getLexicalForm literal))))
    (.isURIResource node) (.getURI (.asResource node))
    (.isAnon node) (str "_:" (.getId (.asResource node)))
    :else (str node)))

(defn shorten
  "An IRI with its namespace replaced by a prefix, where one is declared."
  [^Model model value]
  (if (string? value) (.shortForm model ^String value) value))

(defn triples
  "Up to limit triples as maps, IRIs shortened. Optionally only those about
  one subject IRI."
  ([model limit] (triples model limit nil))
  ([^Model model limit subject-iri]
   (let [^Resource subject (when subject-iri (.createResource model ^String subject-iri))
         ^Property any-predicate nil
         ^RDFNode any-object nil
         statements (.listStatements model subject any-predicate any-object)]
     (try
       (->> (iterator-seq statements)
            (take limit)
            (mapv (fn [^Statement statement]
                    {:subject (shorten model (node->value (.getSubject statement)))
                     :predicate (shorten model (node->value (.getPredicate statement)))
                     :object (shorten model (node->value (.getObject statement)))})))
       (finally (.close statements))))))

;; SPARQL

(defn select
  "Runs a SPARQL SELECT against the model and returns its rows as maps keyed
  by variable name, in the order the query asked for them. The prologue's
  prefixes are already declared."
  [^Model model query-text]
  (with-open [execution (QueryExecutionFactory/create
                         (str sparql-prologue "\n" query-text) model)]
    (let [^ResultSet results (.execSelect execution)
          variable-names (vec (.getResultVars results))]
      (loop [result-rows []]
        (if (.hasNext results)
          (let [^QuerySolution solution (.next results)]
            (recur (conj result-rows
                         (apply array-map
                                (mapcat (fn [variable-name]
                                          [(keyword variable-name)
                                           (node->value (.get solution variable-name))])
                                        variable-names)))))
          result-rows)))))

(def queries
  "The questions src/workshop/form13.clj answers in memory, asked in SPARQL."
  {:counts
   "SELECT ?managers ?companies ?holdings ?ownsTriples WHERE {
      { SELECT (COUNT(*) AS ?managers)    WHERE { ?manager a f13:Manager } }
      { SELECT (COUNT(*) AS ?companies)   WHERE { ?company a f13:Company } }
      { SELECT (COUNT(*) AS ?holdings)    WHERE { ?holding a f13:Holding } }
      { SELECT (COUNT(*) AS ?ownsTriples) WHERE { ?manager f13:owns ?company } }
    }"

   ;; form13/manager-degree
   :companies-per-manager
   "SELECT ?managerName (COUNT(DISTINCT ?company) AS ?companies) (SUM(?value) AS ?totalValue)
    WHERE {
      ?holding a f13:Holding ;
               f13:manager ?manager ;
               f13:company ?company ;
               f13:value ?value .
      ?manager f13:managerName ?managerName .
    }
    GROUP BY ?manager ?managerName
    ORDER BY DESC(?companies) ?managerName
    LIMIT 5"

   ;; form13/company-degree. A company has several names, so one is picked
   ;; in a subquery; joining the names in directly would still count right
   ;; (DISTINCT) but would be the wrong habit for a SUM.
   :managers-per-company
   "SELECT ?cusip ?companyName ?managers WHERE {
      { SELECT ?company (COUNT(DISTINCT ?manager) AS ?managers)
        WHERE { ?holding f13:company ?company ; f13:manager ?manager }
        GROUP BY ?company }
      { SELECT ?company (MIN(?name) AS ?companyName)
        WHERE { ?company f13:companyName ?name }
        GROUP BY ?company }
      ?company f13:cusip ?cusip .
    }
    ORDER BY DESC(?managers) ?cusip
    LIMIT 5"

   ;; form13/name-variants
   :name-variants
   "SELECT ?cusip (COUNT(?name) AS ?names) (GROUP_CONCAT(?name; separator=' / ') AS ?variants)
    WHERE {
      { SELECT ?cusip ?name
        WHERE { ?company f13:cusip ?cusip ; f13:companyName ?name }
        ORDER BY ?cusip ?name }
    }
    GROUP BY ?cusip
    HAVING (COUNT(?name) > 1)
    ORDER BY ?cusip"

   ;; form13/holders-of, by CUSIP: a name would pick one of three spellings.
   :holders-of-apple
   "SELECT ?managerName ?value ?shares WHERE {
      ?holding f13:company company:037833100 ;
               f13:manager/f13:managerName ?managerName ;
               f13:value ?value ;
               f13:shares ?shares .
    }
    ORDER BY DESC(?value)"

   ;; The same degree from the bare triple: shorter, and blind to value,
   ;; shares and quarter.
   :companies-per-manager-bare
   "SELECT ?managerName (COUNT(?company) AS ?companies) WHERE {
      ?manager f13:owns ?company ; f13:managerName ?managerName .
    }
    GROUP BY ?manager ?managerName
    ORDER BY DESC(?companies) ?managerName
    LIMIT 5"

   ;; Managers who hold the same companies: two hops in Cypher, two triple
   ;; patterns here.
   :shared-holdings
   "SELECT ?firstManager ?secondManager (COUNT(?company) AS ?shared) WHERE {
      ?first f13:owns ?company ; f13:managerName ?firstManager .
      ?second f13:owns ?company ; f13:managerName ?secondManager .
      FILTER (?firstManager < ?secondManager)
    }
    GROUP BY ?firstManager ?secondManager
    ORDER BY DESC(?shared) ?firstManager ?secondManager
    LIMIT 5"})

(defn ask
  "Runs one of `queries` by name."
  [model query-name]
  (select model (get queries query-name)))

;; RDF-star, for comparison. Not what the load uses.

(def annotated-example
  "One holding with the properties on the triple itself, in Turtle 1.2
  annotation syntax. Whether this parses depends on the Jena version; the
  gate reports what happened and does not depend on it."
  (str "PREFIX f13: <" vocabulary-iri ">\n"
       "PREFIX manager: <" manager-iri-prefix ">\n"
       "PREFIX company: <" company-iri-prefix ">\n"
       "manager:Example f13:owns company:037833100\n"
       "  {| f13:value 465999000.0 ; f13:shares 4602 |} .\n"))

(def annotated-query
  "The annotation read back, in SPARQL 1.2 annotation syntax."
  "SELECT ?manager ?company ?value ?shares WHERE {
     ?manager f13:owns ?company {| f13:value ?value ; f13:shares ?shares |} .
   }")
