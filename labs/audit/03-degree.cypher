// Audit, 2026-10-08. Read-only: the tables in notes/lab-05-bloom.org, from the graph.
MATCH (m:Manager)-[:OWNS]->(c:Company) RETURN m.managerName AS manager, count(DISTINCT c) AS companies ORDER BY companies DESC, manager LIMIT 5;
MATCH (c:Company)<-[:OWNS]-(m:Manager) RETURN c.companyName AS company, c.cusip AS cusip, count(DISTINCT m) AS managers ORDER BY managers DESC, company LIMIT 6;
MATCH (a:Manager)-[:OWNS]->(c:Company)<-[:OWNS]-(b:Manager) WHERE a.managerName < b.managerName RETURN a.managerName AS first, b.managerName AS second, count(DISTINCT c) AS shared ORDER BY shared DESC LIMIT 3;
MATCH (c:Company) WHERE c.companyName STARTS WITH 'SOUTHERN CO' OR c.companyName STARTS WITH 'AT&T' OR c.companyName STARTS WITH 'AT & T' RETURN c.cusip AS cusip, c.companyName AS company ORDER BY company;
