package com.unciv.logic.automation.unit

import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.automation.Automation
import com.unciv.logic.automation.ThreatLevel
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.automation.unit.UnitAutomation.wander
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.BFS
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.Terrain
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.screens.worldscreen.unit.actions.UnitActions
import com.unciv.utils.Log
import com.unciv.utils.debug

private object WorkerAutomationConst {
    /** BFS max size is determined by the aerial distance of two cities to connect, padded with this */
    // two tiles longer than the distance to the nearest connected city should be enough as the 'reach' of a BFS is increased by blocked tiles
    const val maxBfsReachPadding = 2
}

/**
 * Contains the logic for worker automation.
 *
 * This is instantiated from [Civilization.getWorkerAutomation] and cached there.
 *
 * @param civInfo       The Civilization - data common to all automated workers is cached once per Civ
 * @param cachedForTurn The turn number this was created for - a recreation of the instance is forced on different turn numbers
 */
class WorkerAutomation(
    val civInfo: Civilization,
    val cachedForTurn: Int,
    cloningSource: WorkerAutomation? = null
) {
    ///////////////////////////////////////// Cached data /////////////////////////////////////////

    private val ruleSet = civInfo.gameInfo.ruleset

    /** Caches road to build for connecting cities unless option is off or ruleset removed all roads */
    private val bestRoadAvailable: RoadStatus =
        cloningSource?.bestRoadAvailable ?:
        //Player can choose not to auto-build roads & railroads.
        if (civInfo.isHuman() && !UncivGame.Current.settings.autoBuildingRoads)
            RoadStatus.None
        else civInfo.tech.getBestRoadAvailable()

    /** Civ-wide list of unconnected Cities, sorted by closest to capital first */
    private val citiesThatNeedConnecting: List<City> by lazy {
        val result = civInfo.cities.asSequence()
            .filter {
                it.population.population > 3
                        && !it.isCapital() && !it.isBeingRazed // Cities being razed should not be connected.
                        && !it.cityStats.isConnectedToCapital(bestRoadAvailable)
            }.sortedBy {
                it.getCenterTile().aerialDistanceTo(civInfo.getCapital()!!.getCenterTile())
            }.toList()
        if (Log.shouldLog()) {
            debug("WorkerAutomation citiesThatNeedConnecting for ${civInfo.civName} turn $cachedForTurn:")
            if (result.isEmpty())
                debug("\tempty")
            else result.forEach {
                debug("\t${it.name}")
            }
        }
        result
    }

    /** Civ-wide list of _connected_ Cities, unsorted */
    private val tilesOfConnectedCities: List<Tile> by lazy {
        val result = civInfo.cities.asSequence()
            .filter { it.isCapital() || it.cityStats.isConnectedToCapital(bestRoadAvailable) }
            .map { it.getCenterTile() }
            .toList()
        if (Log.shouldLog()) {
            debug("WorkerAutomation tilesOfConnectedCities for ${civInfo.civName} turn $cachedForTurn:")
            if (result.isEmpty())
                debug("\tempty")
            else result.forEach {
                debug("\t$it")    //  ${it.getCity()?.name} included in Tile toString()
            }
        }
        result
    }

    /** Caches BFS by city locations (cities needing connecting).
     *
     *  key: The city to connect from as [hex position][Vector2].
     *
     *  value: The [BFS] searching from that city, whether successful or not.
     */
    //todo: If BFS were to deal in vectors instead of Tiles, we could copy this on cloning
    private val bfsCache = HashMap<Vector2, BFS>()

    //todo: UnitMovementAlgorithms.canReach still very expensive and could benefit from caching, it's not using BFS


    ///////////////////////////////////////// Helpers /////////////////////////////////////////

    companion object {
        /** Maps to instance [WorkerAutomation.automateWorkerAction] knowing only the MapUnit */
        fun automateWorkerAction(unit: MapUnit) {
            unit.civ.getWorkerAutomation().automateWorkerAction(unit)
        }

        /** Convenience shortcut supports old calling syntax for [WorkerAutomation.getPriority] */
        fun getPriority(tile: Tile, civInfo: Civilization): Int {
            return civInfo.getWorkerAutomation().getPriority(tile)
        }

        /** Convenience shortcut supports old calling syntax for [WorkerAutomation.evaluateFortPlacement] */
        fun evaluateFortPlacement(tile: Tile, civInfo: Civilization, isCitadel: Boolean): Boolean {
            return civInfo.getWorkerAutomation().evaluateFortPlacement(tile, isCitadel)
        }

        /** For console logging only */
        private fun MapUnit.label() = toString() + " " + getTile().position.toString()
    }


    ///////////////////////////////////////// Methods /////////////////////////////////////////
    /**
     * Automate one Worker - decide what to do and where, move, start or continue work.
     */
    fun automateWorkerAction(unit: MapUnit) {
        val currentTile = unit.getTile()
        val tileToWork = findTileToWork(unit)

        if (getPriority(tileToWork, civInfo) < 3) { // building roads is more important
            if (tryConnectingCities(unit)) return
        }

        if (tileToWork != currentTile) {
            debug("WorkerAutomation: %s -> head towards %s", unit.label(), tileToWork)
            val reachedTile = unit.movement.headTowards(tileToWork)
            if (reachedTile != currentTile) unit.doAction() // otherwise, we get a situation where the worker is automated, so it tries to move but doesn't, then tries to automate, then move, etc, forever. Stack overflow exception!
            // If there's move still left, perform action
            // Unit may stop due to Enemy Unit within walking range during doAction() call
            if (unit.currentMovement > 0 && reachedTile == tileToWork) {
                if (reachedTile.isPillaged()) {
                    debug("WorkerAutomation: ${unit.label()} -> repairs $currentTile")
                    UnitActions.getRepairAction(unit).invoke()
                    return
                }
                if (currentTile.improvementInProgress == null && currentTile.isLand
                        && tileCanBeImproved(unit, currentTile)
                ) {
                    debug("WorkerAutomation: ${unit.label()} -> start improving $currentTile")
                    return currentTile.startWorkingOnImprovement(
                        chooseImprovement(unit, currentTile)!!, civInfo, unit
                    )
                }
            }
            return
        }

        if (currentTile.isPillaged()) {
            debug("WorkerAutomation: ${unit.label()} -> repairs $currentTile")
            UnitActions.getRepairAction(unit).invoke()
            return
        }

        if (currentTile.improvementInProgress == null && currentTile.isLand
            && tileCanBeImproved(unit, currentTile)) {
            debug("WorkerAutomation: ${unit.label()} -> start improving $currentTile")
            return currentTile.startWorkingOnImprovement(chooseImprovement(unit, currentTile)!!, civInfo, unit)
        }

        if (currentTile.improvementInProgress != null) return // we're working!
        if (tryConnectingCities(unit)) return //nothing to do, try again to connect cities

        val citiesToNumberOfUnimprovedTiles = HashMap<String, Int>()
        for (city in unit.civ.cities) {
            citiesToNumberOfUnimprovedTiles[city.id] = city.getTiles()
                .count { it.isLand && it.civilianUnit == null && (tileCanBeImproved(unit, it) || it.isPillaged()) }
        }

        val mostUndevelopedCity = unit.civ.cities.asSequence()
            .filter { citiesToNumberOfUnimprovedTiles[it.id]!! > 0 }
            .sortedByDescending { citiesToNumberOfUnimprovedTiles[it.id] }
            .firstOrNull { unit.movement.canReach(it.getCenterTile()) } //goto most undeveloped city

        if (mostUndevelopedCity != null && mostUndevelopedCity != currentTile.owningCity) {
            debug("WorkerAutomation: %s -> head towards undeveloped city %s", unit.label(), mostUndevelopedCity.name)
            val reachedTile = unit.movement.headTowards(mostUndevelopedCity.getCenterTile())
            if (reachedTile != currentTile) unit.doAction() // since we've moved, maybe we can do something here - automate
            return
        }

        debug("WorkerAutomation: %s -> nothing to do", unit.label())
        unit.civ.addNotification("${unit.shortDisplayName()} has no work to do.", currentTile.position, NotificationCategory.Units, unit.name, "OtherIcons/Sleep")

        // Idle CS units should wander so they don't obstruct players so much
        if (unit.civ.isCityState())
            wander(unit, stayInTerritory = true)
    }

    /**
     * Looks for work connecting cities
     * @return whether we actually did anything
     */
    private fun tryConnectingCities(unit: MapUnit): Boolean {
        if (bestRoadAvailable == RoadStatus.None || citiesThatNeedConnecting.isEmpty()) return false

        // Since further away cities take longer to get to and - most importantly - the canReach() to them is very long,
        // we order cities by their closeness to the worker first, and then check for each one whether there's a viable path
        // it can take to an existing connected city.
        val candidateCities = citiesThatNeedConnecting.asSequence().filter {
            // Cities that are too far away make the canReach() calculations devastatingly long
            it.getCenterTile().aerialDistanceTo(unit.getTile()) < 20
        }
        if (candidateCities.none()) return false // do nothing.

        val isCandidateTilePredicate = { it: Tile -> it.isLand && unit.movement.canPassThrough(it) }
        val currentTile = unit.getTile()
        val cityTilesToSeek = ArrayList(tilesOfConnectedCities.sortedBy { it.aerialDistanceTo(currentTile) })

        for (toConnectCity in candidateCities) {
            val toConnectTile = toConnectCity.getCenterTile()
            val bfs: BFS = bfsCache[toConnectTile.position] ?:
                BFS(toConnectTile, isCandidateTilePredicate).apply {
                    maxSize = HexMath.getNumberOfTilesInHexagon(
                        WorkerAutomationConst.maxBfsReachPadding +
                            tilesOfConnectedCities.minOf { it.aerialDistanceTo(toConnectTile) }
                    )
                    bfsCache[toConnectTile.position] = this@apply
                }

            while (true) {
                for (cityTile in cityTilesToSeek.toList()) { // copy since we change while running
                    if (!bfs.hasReachedTile(cityTile)) continue
                    // we have a winner!
                    val pathToCity = bfs.getPathTo(cityTile)
                    val roadableTiles = pathToCity.filter { it.getUnpillagedRoad() < bestRoadAvailable }
                    val tileToConstructRoadOn: Tile
                    if (currentTile in roadableTiles) tileToConstructRoadOn =
                        currentTile
                    else {
                        val reachableTile = roadableTiles
                            .sortedBy { it.aerialDistanceTo(unit.getTile()) }
                            .firstOrNull {
                                unit.movement.canMoveTo(it) && unit.movement.canReach(it)
                            }
                        if (reachableTile == null) {
                            cityTilesToSeek.remove(cityTile) // Apparently we can't reach any of these tiles at all
                            continue
                        }
                        tileToConstructRoadOn = reachableTile
                        unit.movement.headTowards(tileToConstructRoadOn)
                    }
                    if (unit.currentMovement > 0 && currentTile == tileToConstructRoadOn
                        && currentTile.improvementInProgress != bestRoadAvailable.name) {
                        val improvement = bestRoadAvailable.improvement(ruleSet)!!
                        tileToConstructRoadOn.startWorkingOnImprovement(improvement, civInfo, unit)
                    }
                    debug("WorkerAutomation: %s -> connect city %s to %s on %s",
                        unit.label(), bfs.startingPoint.getCity()?.name, cityTile.getCity()!!.name, tileToConstructRoadOn)
                    return true
                }
                if (bfs.hasEnded()) break // We've found another city that this one can connect to
                bfs.nextStep()
            }
            debug("WorkerAutomation: ${unit.label()} -> connect city ${bfs.startingPoint.getCity()?.name} failed at BFS size ${bfs.size()}")
        }

        return false
    }

    /**
     * Looks for a worthwhile tile to improve
     * @return The current tile if no tile to work was found
     */
    private fun findTileToWork(unit: MapUnit): Tile {
        val currentTile = unit.getTile()
        val workableTiles = currentTile.getTilesInDistance(4)
                .filter {
                    (it.civilianUnit == null || it == currentTile)
                            && (it.owningCity == null || it.getOwner()==civInfo)
                            && (tileCanBeImproved(unit, it) || it.isPillaged())
                            && it.getTilesInDistance(2)  // don't work in range of enemy cities
                        .none { tile -> tile.isCityCenter() && tile.getCity()!!.civ.isAtWarWith(civInfo) }
                            && it.getTilesInDistance(3)  // don't work in range of enemy units
                        .none { tile -> tile.militaryUnit != null && tile.militaryUnit!!.civ.isAtWarWith(civInfo)}
                }
                .sortedByDescending { getPriority(it) }

        // the tile needs to be actually reachable - more difficult than it seems,
        // which is why we DON'T calculate this for every possible tile in the radius,
        // but only for the tile that's about to be chosen.
        val selectedTile = workableTiles.firstOrNull { unit.movement.canReach(it) }

        return if (selectedTile != null
                && getPriority(selectedTile) > 1
                && (!workableTiles.contains(currentTile)
                    || getPriority(selectedTile) > getPriority(currentTile)))
            selectedTile
        else currentTile
    }

    /**
     * Tests if tile can be improved by a specific unit, or if no unit is passed, any unit at all
     * (but does not check whether the ruleset contains any unit capable of it)
     */
    private fun tileCanBeImproved(unit: MapUnit, tile: Tile): Boolean {
        if (!tile.isLand || tile.isImpassible() || tile.isCityCenter())
            return false
        val city = tile.getCity()
        if (city == null || city.civ != civInfo)
            return false
        if (!city.tilesInRange.contains(tile)
                && !tile.hasViewableResource(civInfo)
                && civInfo.cities.none { it.getCenterTile().aerialDistanceTo(tile) <= 3 })
            return false // unworkable tile

        val junkImprovement = tile.getTileImprovement()?.hasUnique(UniqueType.AutomatedWorkersWillReplace)
        if (tile.improvement != null && junkImprovement == false
                && !UncivGame.Current.settings.automatedWorkersReplaceImprovements
                && unit.civ.isHuman())
            return false



        if (tile.improvement == null || junkImprovement == true) {
            if (tile.improvementInProgress != null && unit.canBuildImprovement(tile.getTileImprovementInProgress()!!, tile)) return true
            val chosenImprovement = chooseImprovement(unit, tile)
            if (chosenImprovement != null && tile.improvementFunctions.canBuildImprovement(chosenImprovement, civInfo) && unit.canBuildImprovement(chosenImprovement, tile)) return true
        } else if (!tile.containsGreatImprovement() && tile.hasViewableResource(civInfo)
            && tile.tileResource.isImprovedBy(tile.improvement!!)
            && (chooseImprovement(unit, tile) // if the chosen improvement is not null and buildable
                .let { it != null && tile.improvementFunctions.canBuildImprovement(it, civInfo) && unit.canBuildImprovement(it, tile)}))
            return true
        return false // couldn't find anything to construct here
    }

    /**
     * Calculate a priority for improving a tile
     */
    private fun getPriority(tile: Tile): Int {
        var priority = 0
        if (tile.getOwner() == civInfo) {
            priority += 2
            if (tile.providesYield()) priority += 3
            if (tile.isPillaged()) priority += 1
        }
        // give a minor priority to tiles that we could expand onto
        else if (tile.getOwner() == null && tile.neighbors.any { it.getOwner() == civInfo })
            priority += 1

        if (priority != 0 && tile.hasViewableResource(civInfo)) priority += 1
        return priority
    }

    /**
     * Determine the improvement appropriate to a given tile and worker
     */
    private fun chooseImprovement(unit: MapUnit, tile: Tile): TileImprovement? {

        val potentialTileImprovements = ruleSet.tileImprovements.filter {
            unit.canBuildImprovement(it.value, tile)
                    && tile.improvementFunctions.canBuildImprovement(it.value, civInfo)
                    && (it.value.uniqueTo == null || it.value.uniqueTo == unit.civ.civName)
        }
        if (potentialTileImprovements.isEmpty()) return null

        fun getRankingWithImprovement(improvementName: String): Float {
            val improvement = ruleSet.tileImprovements[improvementName]!!
            val stats = tile.stats.getImprovementStats(improvement, civInfo, tile.getCity())
            return Automation.rankStatsValue(stats, unit.civ)
        }

        val bestBuildableImprovement = potentialTileImprovements.values.asSequence()
            .map { Pair(it, getRankingWithImprovement(it.name)) }
            .filter { it.second > 0f }
            .maxByOrNull { it.second }?.first

        val lastTerrain = tile.getLastTerrain()

        fun isUnbuildableAndRemovable(terrain: Terrain): Boolean = terrain.unbuildable
                && ruleSet.tileImprovements.containsKey(Constants.remove + terrain.name)


        val improvementStringForResource: String? = when {
            tile.resource == null || !tile.hasViewableResource(civInfo) -> null
            tile.terrainFeatures.isNotEmpty()
                    && isUnbuildableAndRemovable(lastTerrain)
                    && !tile.providesResources(civInfo)
                    && !isResourceImprovementAllowedOnFeature(tile, potentialTileImprovements) -> Constants.remove + lastTerrain.name
            else -> tile.tileResource.getImprovements().filter { it in potentialTileImprovements || it==tile.improvement }
                .maxByOrNull { getRankingWithImprovement(it) }
        }

        // After gathering all the data, we conduct the hierarchy in one place
        val improvementString = when {
            tile.improvementInProgress != null -> tile.improvementInProgress!!
            improvementStringForResource != null -> if (improvementStringForResource==tile.improvement) null else improvementStringForResource
            // if this is a resource that HAS an improvement, but this unit can't build it, don't waste your time
            tile.resource != null && tile.tileResource.getImprovements().any() -> return null
            bestBuildableImprovement == null -> null

            tile.improvement!=null && getRankingWithImprovement(tile.improvement!!) > getRankingWithImprovement(bestBuildableImprovement.name)
                -> null // What we have is better, even if it's pillaged we should repair it

            lastTerrain.let {
                isUnbuildableAndRemovable(it) &&
                        (Automation.rankStatsValue(it, civInfo) < 0 || it.hasUnique(UniqueType.NullifyYields) )
            } -> Constants.remove + lastTerrain.name

            else -> bestBuildableImprovement.name
        }
        return ruleSet.tileImprovements[improvementString] // For mods, the tile improvement may not exist, so don't assume.
    }

    /**
     * Checks whether the improvement matching the tile resource requires any terrain feature to be removed first.
     *
     * Assumes the caller ensured that terrainFeature and resource are both present!
     */
    private fun isResourceImprovementAllowedOnFeature(
        tile: Tile,
        potentialTileImprovements: Map<String, TileImprovement>
    ): Boolean {
        return tile.tileResource.getImprovements().any { resourceImprovementName ->
            if (resourceImprovementName !in potentialTileImprovements) return@any false
            val resourceImprovement = potentialTileImprovements[resourceImprovementName]!!
            tile.terrainFeatures.any { resourceImprovement.isAllowedOnFeature(it) }
        }
    }

    /**
     * Checks whether a given tile allows a Fort and whether a Fort may be undesirable (without checking surroundings).
     *
     * -> Checks: city, already built, resource, great improvements.
     * Used only in [evaluateFortPlacement].
     */
    private fun isAcceptableTileForFort(tile: Tile): Boolean {
        //todo Should this not also check impassable and the fort improvement's terrainsCanBeBuiltOn/uniques?
        if (tile.isCityCenter() // don't build fort in the city
            || !tile.isLand // don't build fort in the water
            || tile.improvement == Constants.fort // don't build fort if it is already here
            || tile.hasViewableResource(civInfo) // don't build on resource tiles
            || tile.containsGreatImprovement() // don't build on great improvements (including citadel)
        ) return false

        return true
    }

    /**
     * Do we want a Fort [here][tile] considering surroundings?
     * @param  isCitadel Controls within borders check - true also allows 1 tile outside borders
     * @return Yes please build a Fort here
     */
    private fun evaluateFortPlacement(tile: Tile, isCitadel: Boolean): Boolean {
        //todo Is the Citadel code dead anyway? If not - why does the nearestTiles check not respect the param?

        // build on our land only
        if (tile.owningCity?.civ != civInfo &&
                    // except citadel which can be built near-by
                    (!isCitadel || tile.neighbors.all { it.getOwner() != civInfo }) ||
            !isAcceptableTileForFort(tile)) return false

        // if this place is not perfect, let's see if there is a better one
        val nearestTiles = tile.getTilesInDistance(2).filter { it.owningCity?.civ == civInfo }.toList()
        for (closeTile in nearestTiles) {
            // don't build forts too close to the cities
            if (closeTile.isCityCenter()) return false
            // don't build forts too close to other forts
            if (closeTile.improvement != null
                && closeTile.getTileImprovement()!!.hasUnique("Gives a defensive bonus of []%")
                || closeTile.improvementInProgress != Constants.fort) return false
            // there is another better tile for the fort
            if (!tile.isHill() && closeTile.isHill() &&
                isAcceptableTileForFort(closeTile)) return false
        }

        val enemyCivs = civInfo.getKnownCivs()
            .filterNot { it == civInfo || it.cities.isEmpty() || !civInfo.getDiplomacyManager(it).canAttack() }
        // no potential enemies
        if (enemyCivs.isEmpty()) return false

        val threatMapping: (Civilization) -> Int = {
            // the war is already a good nudge to build forts
            (if (civInfo.isAtWarWith(it)) 20 else 0) +
                    // let's check also the force of the enemy
                    when (Automation.threatAssessment(civInfo, it)) {
                        ThreatLevel.VeryLow -> 1 // do not build forts
                        ThreatLevel.Low -> 6 // too close, let's build until it is late
                        ThreatLevel.Medium -> 10
                        ThreatLevel.High -> 15 // they are strong, let's built until they reach us
                        ThreatLevel.VeryHigh -> 20
                    }
        }
        val enemyCivsIsCloseEnough = enemyCivs.filter { NextTurnAutomation.getMinDistanceBetweenCities(
            civInfo,
            it) <= threatMapping(it) }
        // no threat, let's not build fort
        if (enemyCivsIsCloseEnough.isEmpty()) return false

        // make list of enemy cities as sources of threat
        val enemyCities = mutableListOf<Tile>()
        enemyCivsIsCloseEnough.forEach { enemyCities.addAll(it.cities.map { city -> city.getCenterTile() }) }

        // find closest enemy city
        val closestEnemyCity = enemyCities.minByOrNull { it.aerialDistanceTo(tile) }!!
        val distanceToEnemy = tile.aerialDistanceTo(closestEnemyCity)

        // find closest our city to defend from this enemy city
        val closestOurCity = civInfo.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }!!.getCenterTile()
        val distanceToOurCity = tile.aerialDistanceTo(closestOurCity)

        val distanceBetweenCities = closestEnemyCity.aerialDistanceTo(closestOurCity)

        // let's build fort on the front line, not behind the city
        // +2 is a acceptable deviation from the straight line between cities
        return distanceBetweenCities + 2 > distanceToEnemy + distanceToOurCity
    }

}
