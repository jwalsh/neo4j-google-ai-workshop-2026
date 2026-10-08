// The same subgraph as rows of names, for drawing: one row per edge.
MATCH (home:__Entity__ {id: 'Home'})-[includes]->(member:__Entity__)
OPTIONAL MATCH (member)-[tie]-(other:__Entity__)
WHERE other <> home AND NOT other:Organization
WITH home, includes, member, tie, other
UNWIND [[home, includes, member], [startNode(tie), tie, endNode(tie)]] AS edge
WITH edge WHERE edge[1] IS NOT NULL
RETURN DISTINCT edge[0].id AS source, [label IN labels(edge[0]) WHERE label <> '__Entity__'][0] AS sourceLabel,
       type(edge[1]) AS relationship,
       edge[2].id AS target, [label IN labels(edge[2]) WHERE label <> '__Entity__'][0] AS targetLabel
ORDER BY source, target;
