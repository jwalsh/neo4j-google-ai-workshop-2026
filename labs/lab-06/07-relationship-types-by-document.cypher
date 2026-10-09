// Which relationship types each document's entities carry, by the chunk
// that mentions both ends: the free extraction beside the constrained one.
MATCH (document:Document)<-[:PART_OF]-(chunk:Chunk)-[:HAS_ENTITY]->(source:__Entity__)-[relationship]->(target:__Entity__)<-[:HAS_ENTITY]-(chunk)
RETURN document.fileName AS document, type(relationship) AS type, count(DISTINCT relationship) AS relationships
ORDER BY document, relationships DESC;
