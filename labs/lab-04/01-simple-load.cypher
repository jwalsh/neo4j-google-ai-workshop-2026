// Lab 4, "Simple Load Statement". The statement is the lab's, from
// neo4j-partners/hands-on-lab-neo4j-and-google (Apache 2.0), as run.
LOAD CSV WITH HEADERS FROM "https://storage.googleapis.com/neo4j-datasets/hands-on-lab/form13-2023-05-11.csv" AS row
MERGE (m:Manager {managerName:row.managerName})
MERGE (c:Company {companyName:row.companyName, cusip:row.cusip})
MERGE (m)-[r:OWNS {value:toFloat(row.value), shares:toInteger(row.shares), reportCalendarOrQuarter:date(row.reportCalendarOrQuarter)}]->(c);
