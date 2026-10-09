// Local form of labs/lab-04/04-companies.cypher.
//
// What differs: the CSV comes from $csvUrl, not from the lab's https URL.
// Why: the file is already in data/ and a load should not need the network.
// The lab's statement runs unchanged on a local server too; it is only
// slower by the download. $csvUrl is a file: URL (local-graph.lab/csv-url);
// passing it as a parameter keeps this machine's path out of the file.
LOAD CSV WITH HEADERS FROM $csvUrl AS row
MERGE (c:Company {cusip:row.cusip})
ON CREATE SET c.companyName=row.companyName;
