// Local form of labs/lab-04/05-managers.cypher.
//
// What differs: $csvUrl for the https URL, as in 02-companies.cypher.
LOAD CSV WITH HEADERS FROM $csvUrl AS row
MERGE (m:Manager {managerName:row.managerName});
