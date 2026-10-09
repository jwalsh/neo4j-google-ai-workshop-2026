// Local form of labs/lab-04/03-constraints.cypher.
//
// What differs: IS UNIQUE where the lab has IS NODE KEY.
// Why: a node key is uniqueness plus existence, and both node keys and
// existence constraints are Enterprise features. Community answers the lab's
// statement with "51N27: Key constraint is not supported in community
// edition". Aura is Enterprise. IS UNIQUE gives MERGE the same index to look
// up in and still refuses a second node with the same key; what is lost is
// the refusal of a Company with no cusip at all (experiment 005, attempt 6,
// is that refusal doing its job).
CREATE CONSTRAINT unique_company_id IF NOT EXISTS FOR (p:Company) REQUIRE (p.cusip) IS UNIQUE;
CREATE CONSTRAINT unique_manager IF NOT EXISTS FOR (p:Manager) REQUIRE (p.managerName) IS UNIQUE;
