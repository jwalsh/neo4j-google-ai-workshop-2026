// Audit, 2026-10-08. Read-only: the labels, keys and vector indexes the notes do not mention.
SHOW INDEXES YIELD name, type, labelsOrTypes, properties, options, lastRead, readCount, trackedSince, createStatement WHERE type = 'VECTOR' RETURN name, labelsOrTypes, properties, options.indexConfig AS indexConfig, lastRead, readCount, trackedSince, createStatement ORDER BY name;
MATCH (n) WHERE n:Chunk OR n:__Entity__ OR n:__Community__ RETURN count(n) AS nodesUnderTheUnexplainedLabels;
MATCH (n) WHERE n.embedding IS NOT NULL OR n.data IS NOT NULL OR n.style IS NOT NULL OR n.visualisation IS NOT NULL OR n.name IS NOT NULL OR n.id IS NOT NULL OR n.nodes IS NOT NULL OR n.relationships IS NOT NULL RETURN count(n) AS nodesWithAnUnexplainedKey;
MATCH ()-[r]->() WHERE r.embedding IS NOT NULL OR r.data IS NOT NULL OR r.style IS NOT NULL OR r.name IS NOT NULL OR r.id IS NOT NULL RETURN count(r) AS relationshipsWithAnUnexplainedKey;
SHOW DATABASES YIELD name, type, access, role, currentStatus, default, home RETURN name, type, access, role, currentStatus, default, home;
