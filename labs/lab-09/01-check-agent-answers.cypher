// Lab 9: what the Aura agent's answers can be checked against.
// How many companies have exactly N owning managers, for small N.
MATCH (company:Company)<-[:OWNS]-(manager:Manager)
WITH company, count(DISTINCT manager) AS owners
RETURN owners, count(*) AS companies
ORDER BY owners
LIMIT 6;

// The agent listed these as held by exactly two managers.
MATCH (company:Company)<-[:OWNS]-(manager:Manager)
WHERE company.companyName IN ['2U INC', 'ABM Inds Inc', 'ACCO Brands Corp']
RETURN company.companyName AS company, collect(DISTINCT manager.managerName) AS managers;

// And these as held by exactly three.
MATCH (company:Company)<-[:OWNS]-(manager:Manager)
WHERE company.companyName STARTS WITH 'Alphabet' OR company.companyName STARTS WITH 'Allstate'
RETURN company.companyName AS company, company.cusip AS cusip, count(DISTINCT manager) AS owners;

// The agent's Text2Cypher tool tried m.name and c.name. Neither property exists.
MATCH (manager:Manager) RETURN count(manager.name) AS managersWithName, count(manager.managerName) AS managersWithManagerName;
