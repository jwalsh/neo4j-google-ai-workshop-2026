// Memgraph form of labs/lab-04/03-constraints.cypher. Memgraph 3.13.1.
//
// What differs, and why:
// - IS UNIQUE for IS NODE KEY. Memgraph's parser stops at NODE: "mismatched
//   input 'NODE' expecting UNIQUE". The rest of the Neo4j 5 statement, name
//   and IF NOT EXISTS and FOR ... REQUIRE, it accepts as written.
// - The existence half of a node key is its own statement here, and unlike
//   Neo4j Community, Memgraph Community has it.
// - Two CREATE INDEX statements the lab does not have. In Neo4j a uniqueness
//   constraint is backed by an index and MERGE uses it. In Memgraph it is
//   not: SHOW INDEX INFO is empty after the constraints, and the companies
//   MERGE took 3.9 s without the index and 54 ms with it.
CREATE CONSTRAINT unique_company_id IF NOT EXISTS FOR (p:Company) REQUIRE (p.cusip) IS UNIQUE;
CREATE CONSTRAINT unique_manager IF NOT EXISTS FOR (p:Manager) REQUIRE (p.managerName) IS UNIQUE;
CREATE CONSTRAINT ON (p:Company) ASSERT EXISTS (p.cusip);
CREATE CONSTRAINT ON (p:Manager) ASSERT EXISTS (p.managerName);
CREATE INDEX ON :Company(cusip);
CREATE INDEX ON :Manager(managerName);
