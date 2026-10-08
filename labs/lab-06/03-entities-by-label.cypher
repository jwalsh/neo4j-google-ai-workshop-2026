// What the extraction made, a few of each.
MATCH (entity:__Entity__)
UNWIND [label IN labels(entity) WHERE label <> '__Entity__'] AS label
RETURN label, count(*) AS entities, collect(entity.id)[..6] AS examples
ORDER BY entities DESC;
