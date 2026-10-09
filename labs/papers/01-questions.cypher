// Ours. Questions of the papers graph (workshop.papers/load!).
// Which papers the rest of the reading list leans on.
MATCH (cited:Paper)<-[:CITES]-(citing:Paper)
RETURN cited.arxiv AS arxiv, left(cited.title, 60) AS paper, count(citing) AS citedBy
ORDER BY citedBy DESC, arxiv LIMIT 12;

// Which categories cite which: who builds on whom.
MATCH (a:Category)<-[:IN]-(:Paper)-[:CITES]->(:Paper)-[:IN]->(b:Category)
RETURN a.name AS citing, b.name AS cited, count(*) AS citations
ORDER BY citations DESC LIMIT 15;

// Papers that join the two collections: cited from one, filed in the other.
MATCH (x:Collection)<-[:IN]-(citing:Paper)-[:CITES]->(cited:Paper)-[:IN]->(y:Collection)
WHERE x.name <> y.name AND NOT (citing)-[:IN]->(y)
RETURN x.name AS fromCollection, left(citing.title, 40) AS citing, left(cited.title, 40) AS cited, y.name AS inCollection
ORDER BY cited LIMIT 15;

// Papers nothing else here cites and that cite nothing here.
MATCH (p:Paper) WHERE NOT (p)-[:CITES]-(:Paper)
OPTIONAL MATCH (p)-[:IN]->(c:Category)
RETURN p.arxiv AS arxiv, c.name AS category, left(p.title, 60) AS paper ORDER BY arxiv;

// Verdicts from the reading guide, by category.
MATCH (p:Paper)-[:IN]->(c:Category) WHERE p.verdict IS NOT NULL
RETURN c.name AS category, p.verdict AS verdict, count(*) AS papers ORDER BY category, verdict;

// Authors on more than one paper.
MATCH (a:Author)-[:WROTE]->(p:Paper)
WITH a, count(p) AS papers WHERE papers > 1
RETURN a.name AS author, papers ORDER BY papers DESC, author LIMIT 15;
