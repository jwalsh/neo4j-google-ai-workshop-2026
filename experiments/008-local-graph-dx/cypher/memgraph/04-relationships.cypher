// Memgraph form of labs/lab-04/06-relationships.cypher. Memgraph 3.13.1.
//
// What differs, and why:
// - The LOAD CSV clause order and the path, as in 02-companies.cypher.
// - USING PERIODIC COMMIT 1000 for CALL (row) { } IN TRANSACTIONS OF 1000
//   ROWS. Memgraph accepts the lab's form and loads the same 7,381
//   relationships with it, but took 8.8 s where this takes 58 ms. The cost
//   is the subquery, not the batching: CALL (row) { } with no IN
//   TRANSACTIONS took 9.4 s, and the body with no subquery at all 89 ms.
// - date(), toFloat() and toInteger() are the lab's and run unchanged.
USING PERIODIC COMMIT 1000
LOAD CSV FROM $csvUrl WITH HEADER AS row
MATCH (m:Manager {managerName: row.managerName})
MATCH (c:Company {cusip: row.cusip})
MERGE (m)-[r:OWNS {reportCalendarOrQuarter: date(row.reportCalendarOrQuarter)}]->(c)
SET r.value = toFloat(row.value),
    r.shares = toInteger(row.shares);
