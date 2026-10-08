// Which labels carry an embedding, and how long it is.
MATCH (n) WHERE n.embedding IS NOT NULL
UNWIND labels(n) AS label
RETURN label, count(*) AS nodes, min(size(n.embedding)) AS dimensions
ORDER BY label;
