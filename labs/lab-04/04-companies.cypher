// Lab 4: companies, keyed by CUSIP. The lab's statement.
LOAD CSV WITH HEADERS FROM 'https://storage.googleapis.com/neo4j-datasets/hands-on-lab/form13-2023-05-11.csv' AS row
MERGE (c:Company {cusip:row.cusip})
ON CREATE SET c.companyName=row.companyName;
