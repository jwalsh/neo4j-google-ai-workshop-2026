MATCH (origin:Place), (destination:Place)
WHERE origin.name = $fromStation AND destination.name = $toStation
CALL {
  WITH origin
  MATCH (near:Station)-[leg:CONNECTS]->()
  WHERE
    leg.kind <> 'commuter rail' AND
    point.distance(origin.location, near.location) <= 1200
  WITH DISTINCT
    near,
    toInteger(point.distance(origin.location, near.location)) AS walk
  ORDER BY walk
  LIMIT 3
  RETURN collect({station: near, walk: walk}) AS starts
}
CALL {
  WITH destination
  MATCH (near:Station)<-[leg:CONNECTS]-()
  WHERE
    leg.kind <> 'commuter rail' AND
    point.distance(destination.location, near.location) <= 1200
  WITH DISTINCT
    near,
    toInteger(point.distance(destination.location, near.location)) AS walk
  ORDER BY walk
  LIMIT 3
  RETURN collect({station: near, walk: walk}) AS ends
}
UNWIND starts AS start
UNWIND ends AS end
WITH start, end, starts, ends
WHERE start.station <> end.station
WITH
  start.station AS boardAt,
  end.station AS alightAt,
  start.walk AS walkToBoard,
  end.walk AS walkFromAlight,
  [other IN starts WHERE other.walk < start.walk | other.station] AS nearerStarts,
  [other IN ends WHERE other.walk < end.walk | other.station] AS nearerEnds
MATCH journey = ALLSHORTESTPATHS ((boardAt)-[:CONNECTS*..60]->(alightAt))
WHERE all(leg IN relationships(journey) WHERE leg.kind <> 'commuter rail')
WITH
  boardAt,
  alightAt,
  walkToBoard,
  walkFromAlight,
  nearerStarts,
  nearerEnds,
  relationships(journey) AS legs,
  nodes(journey) AS calls
WHERE
  none(passed IN calls[1..] WHERE passed IN nearerStarts) AND
  none(passed IN calls[..-1] WHERE passed IN nearerEnds)
WITH
  boardAt,
  alightAt,
  walkToBoard,
  walkFromAlight,
  size(legs) AS stops,
  legs,
  calls,
  [
    position IN range(0, size(legs) - 1)
    WHERE
      position = size(legs) - 1 OR
      legs[position].route <> legs[position + 1].route
  ] AS lastLegs
WITH
  boardAt,
  alightAt,
  walkToBoard,
  walkFromAlight,
  stops,
  size(lastLegs) - 1 AS changes,
  [position IN lastLegs | legs[position].line] AS lines,
  [position IN lastLegs | calls[position + 1].name] AS alightings,
  [position IN lastLegs | legs[position].routeName] AS routes
WITH
  boardAt,
  alightAt,
  walkToBoard,
  walkFromAlight,
  stops,
  changes,
  lines,
  alightings,
  collect(DISTINCT routes) AS variants
WITH
  boardAt,
  alightAt,
  walkToBoard,
  walkFromAlight,
  stops,
  min(changes) AS fewestChanges,
  collect({
    changes: changes,
    lines: lines,
    alightings: alightings,
    variants: variants
  }) AS ways
UNWIND ways AS way
WITH boardAt, alightAt, walkToBoard, walkFromAlight, stops, way
WHERE way.changes = fewestChanges
WITH
  boardAt,
  alightAt,
  walkToBoard,
  walkFromAlight,
  stops,
  way.changes AS changes,
  way.lines AS linesRidden,
  [
    position IN range(0, size(way.lines) - 1)
    | CASE
      WHEN
        all(
          variant IN way.variants
          WHERE variant[position] = way.variants[0][position]
        )
        THEN way.variants[0][position]
      ELSE way.lines[position]
    END +
    ' to ' +
    way.alightings[position]
  ] AS rides
WITH
  *,
  toInteger(
    round(stops * 2 + changes * 5 + (walkToBoard + walkFromAlight) / 80.0)) AS aboutMinutes
ORDER BY aboutMinutes, changes, stops
WITH
  linesRidden,
  collect({
    boardAt: boardAt.name,
    alightAt: alightAt.name,
    walkToBoard: walkToBoard,
    walkFromAlight: walkFromAlight,
    stops: stops,
    changes: changes,
    rides: rides,
    aboutMinutes: aboutMinutes
  })[0] AS best
ORDER BY best.aboutMinutes, best.changes, best.stops
LIMIT $limit
RETURN
  CASE
    WHEN
      best.walkToBoard > 0
      THEN 'walk ' + best.walkToBoard + ' m to ' + best.boardAt
    ELSE best.boardAt
  END AS start,
  reduce(
    text = '',
    ride IN
    best.rides |
      text +
      CASE
        WHEN text = '' THEN ''
        ELSE ', then '
      END + ride) AS ride,
  CASE
    WHEN
      best.walkFromAlight > 0
      THEN 'walk ' + best.walkFromAlight + ' m from ' + best.alightAt
    ELSE ''
  END AS finish,
  best.stops AS stops,
  best.changes AS changes,
  best.aboutMinutes AS aboutMinutes
