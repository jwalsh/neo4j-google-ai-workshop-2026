// Local form of labs/lab-04/06-relationships.cypher.
//
// What differs: $csvUrl for the https URL. The statement is otherwise the
// lab's, CALL (row) { } IN TRANSACTIONS included.
// What had to change is the caller, not the Cypher: Neo4j runs this only in
// an implicit (auto-commit) transaction, and workshop.neo4j/query opens a
// managed one. local-graph.lab/run-file sends it through a session instead.
// The same is true against Aura; it is not a local difference.
LOAD CSV WITH HEADERS FROM $csvUrl AS row
CALL (row) {
    MATCH (m:Manager {managerName: row.managerName})
    MATCH (c:Company {cusip: row.cusip})
    MERGE (m)-[r:OWNS {reportCalendarOrQuarter: date(row.reportCalendarOrQuarter)}]->(c)
    SET r.value = toFloat(row.value),
    r.shares = toInteger(row.shares)
}
IN TRANSACTIONS OF 1000 ROWS;
