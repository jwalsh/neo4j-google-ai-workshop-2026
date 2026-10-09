// Audit, 2026-10-08. Read-only: counts, and anything Lab 4 did not put there.
MATCH (n) RETURN labels(n) AS labels, count(*) AS nodes ORDER BY nodes DESC;
MATCH (n) UNWIND keys(n) AS propertyKey RETURN labels(n) AS labels, propertyKey, count(*) AS nodes ORDER BY labels, propertyKey;
MATCH ()-[r]->() UNWIND keys(r) AS propertyKey RETURN type(r) AS type, propertyKey, count(*) AS relationships ORDER BY type, propertyKey;
MATCH (source)-[r]->(target) RETURN labels(source) AS source, type(r) AS type, labels(target) AS target, count(*) AS relationships ORDER BY relationships DESC;
MATCH (n) WHERE NOT (n)--() RETURN labels(n) AS labels, count(*) AS isolated;
MATCH (n) WHERE NOT n:Company AND NOT n:Manager RETURN labels(n) AS labels, count(*) AS unexpected;
MATCH (c:Company) WHERE c.companyName IS NULL OR c.cusip IS NULL RETURN count(c) AS companiesMissingAProperty;
MATCH ()-[r:OWNS]->() WHERE r.value IS NULL OR r.shares IS NULL OR r.reportCalendarOrQuarter IS NULL RETURN count(r) AS ownsMissingAProperty;
MATCH ()-[r:OWNS]->() RETURN toString(r.reportCalendarOrQuarter) AS quarter, count(*) AS relationships, sum(r.value) AS totalValue ORDER BY quarter;
MATCH (m:Manager)-[r:OWNS]->(c:Company) WITH m, c, count(r) AS parallel WHERE parallel > 1 RETURN count(*) AS pairsWithParallelOwns;
