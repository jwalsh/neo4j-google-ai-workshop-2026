// Audit, 2026-10-08. Read-only: what the database says it holds.
CALL dbms.components() YIELD name, versions, edition RETURN name, versions, edition;
CALL db.labels() YIELD label RETURN label ORDER BY label;
CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType ORDER BY relationshipType;
CALL db.propertyKeys() YIELD propertyKey RETURN propertyKey ORDER BY propertyKey;
SHOW CONSTRAINTS YIELD name, type, entityType, labelsOrTypes, properties, ownedIndex RETURN name, type, entityType, labelsOrTypes, properties, ownedIndex ORDER BY name;
SHOW INDEXES YIELD name, type, state, populationPercent, entityType, labelsOrTypes, properties, owningConstraint RETURN name, type, state, populationPercent, entityType, labelsOrTypes, properties, owningConstraint ORDER BY name;
