// The picture Lab 6 ends on: the Home category and the products under it,
// with whatever else each product is tied to apart from the company.
MATCH (home:__Entity__ {id: 'Home'})-[includes]->(member:__Entity__)
OPTIONAL MATCH (member)-[tie]-(other:__Entity__)
WHERE other <> home AND NOT other:Organization
RETURN home, includes, member, tie, other;
