// Audit, 2026-10-08. Read-only: which Graph Analytics procedures the instance offers, and its sessions.
SHOW PROCEDURES YIELD name WHERE name STARTS WITH 'gds.session' OR name STARTS WITH 'gds.degree' OR name STARTS WITH 'gds.graph.project' OR name STARTS WITH 'gds.aura' RETURN name ORDER BY name;
SHOW PROCEDURES YIELD name, signature WHERE name = 'gds.session.list' RETURN signature;
CALL gds.session.list() YIELD name, memory, status, creationTime, expiryDate, ttl, cloudLocation, terminationReason RETURN name, memory, status, toString(creationTime) AS created, toString(expiryDate) AS expires, toString(ttl) AS ttl, cloudLocation, terminationReason;
