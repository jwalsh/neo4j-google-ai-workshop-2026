// Memgraph form of labs/lab-04/04-companies.cypher.
//
// What differs, and why:
// - LOAD CSV FROM ... WITH HEADER, where Neo4j has LOAD CSV WITH HEADERS
//   FROM. The lab's order fails to parse: "missing FROM at 'WITH'".
// - $csvUrl is a plain path inside the container (/import-data/...), not a
//   file: URL; Memgraph answers "CSV file not found" to file:///.
LOAD CSV FROM $csvUrl WITH HEADER AS row
MERGE (c:Company {cusip:row.cusip})
ON CREATE SET c.companyName=row.companyName;
