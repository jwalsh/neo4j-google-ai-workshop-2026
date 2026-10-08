// Lab 6 ends on the Home category and what it includes.
MATCH (category:__Entity__)-[relationship]->(member:__Entity__)
WHERE toLower(category.id) CONTAINS 'home'
RETURN category.id AS category, labels(category) AS labels, type(relationship) AS relationship, member.id AS member
ORDER BY category, member;
