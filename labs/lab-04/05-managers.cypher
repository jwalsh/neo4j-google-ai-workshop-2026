// Lab 4: managers, keyed by name. The lab's statement.
LOAD CSV WITH HEADERS FROM 'https://storage.googleapis.com/neo4j-datasets/hands-on-lab/form13-2023-05-11.csv' AS row
MERGE (m:Manager {managerName:row.managerName});
