// Memgraph form of labs/lab-04/05-managers.cypher.
//
// What differs: the LOAD CSV clause order and the path, as in
// 02-companies.cypher.
LOAD CSV FROM $csvUrl WITH HEADER AS row
MERGE (m:Manager {managerName:row.managerName});
