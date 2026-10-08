// Lab 4: OWNS relationships, in batches. The lab's statement.
LOAD CSV WITH HEADERS FROM 'https://storage.googleapis.com/neo4j-datasets/hands-on-lab/form13-2023-05-11.csv' AS row
CALL (row) {
    MATCH (m:Manager {managerName: row.managerName})
    MATCH (c:Company {cusip: row.cusip})
    MERGE (m)-[r:OWNS {reportCalendarOrQuarter: date(row.reportCalendarOrQuarter)}]->(c)
    SET r.value = toFloat(row.value),
    r.shares = toInteger(row.shares)
}
IN TRANSACTIONS OF 1000 ROWS;
