package com.unciv.logic.civilization.managers

import com.unciv.UncivGame
import com.unciv.logic.VictoryData
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.city.managers.CityTurnManager
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.map.mapunit.UnitTurnManager
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.models.ruleset.ModOptionsConstants
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unique.endTurn
import com.unciv.ui.components.MayaCalendar
import java.util.*
import kotlin.math.max
import kotlin.math.min

class TurnManager(val civInfo: Civilization) {


    fun startTurn() {
        civInfo.civConstructions.startTurn()
        civInfo.attacksSinceTurnStart.clear()
        civInfo.updateStatsForNextTurn() // for things that change when turn passes e.g. golden age, city state influence

        // Do this after updateStatsForNextTurn but before cities.startTurn
        if (civInfo.playerType == PlayerType.AI && civInfo.gameInfo.ruleset.modOptions.uniques.contains(
                    ModOptionsConstants.convertGoldToScience))
            NextTurnAutomation.automateGoldToSciencePercentage(civInfo)

        // Generate great people at the start of the turn,
        // so they won't be generated out in the open and vulnerable to enemy attacks before you can control them
        if (civInfo.cities.isNotEmpty()) { //if no city available, addGreatPerson will throw exception
            val greatPerson = civInfo.greatPeople.getNewGreatPerson()
            if (greatPerson != null && civInfo.gameInfo.ruleset.units.containsKey(greatPerson))
                civInfo.units.addUnit(greatPerson)
            civInfo.religionManager.startTurn()
            if (civInfo.isLongCountActive())
                MayaCalendar.startTurnForMaya(civInfo)
        }

        civInfo.cache.updateViewableTiles() // adds explored tiles so that the units will be able to perform automated actions better
        civInfo.cache.updateCitiesConnectedToCapital()
        startTurnFlags()
        updateRevolts()
        for (city in civInfo.cities) CityTurnManager(city).startTurn()  // Most expensive part of startTurn

        for (unit in civInfo.units.getCivUnits()) UnitTurnManager(unit).startTurn()

        if (civInfo.playerType == PlayerType.Human && UncivGame.Current.settings.automatedUnitsMoveOnTurnStart) {
            civInfo.hasMovedAutomatedUnits = true
            for (unit in civInfo.units.getCivUnits())
                unit.doAction()
        } else civInfo.hasMovedAutomatedUnits = false

        civInfo.cache.updateCivResources() // If you offered a trade last turn, this turn it will have been accepted/declined

        for (tradeRequest in civInfo.tradeRequests.toList()) { // remove trade requests where one of the sides can no longer supply
            val offeringCiv = civInfo.gameInfo.getCivilization(tradeRequest.requestingCiv)
            if (offeringCiv.isDefeated() || !TradeEvaluation().isTradeValid(tradeRequest.trade, civInfo, offeringCiv)) {
                civInfo.tradeRequests.remove(tradeRequest)
                // Yes, this is the right direction. I checked.
                offeringCiv.addNotification("Our proposed trade is no longer relevant!", NotificationCategory.Trade, NotificationIcon.Trade)
            }
        }

        updateWinningCiv()
    }


    private fun startTurnFlags() {
        for (flag in civInfo.flagsCountdown.keys.toList()) {
            // In case we remove flags while iterating
            if (!civInfo.flagsCountdown.containsKey(flag)) continue

            if (flag == CivFlags.CityStateGreatPersonGift.name) {
                val cityStateAllies: List<Civilization> =
                        civInfo.getKnownCivs().filter { it.isCityState() && it.getAllyCiv() == civInfo.civName }
                val givingCityState = cityStateAllies.filter { it.cities.isNotEmpty() }.randomOrNull()

                if (cityStateAllies.isNotEmpty()) civInfo.flagsCountdown[flag] = civInfo.flagsCountdown[flag]!! - 1

                if (civInfo.flagsCountdown[flag]!! < min(cityStateAllies.size, 10) && civInfo.cities.isNotEmpty()
                        && givingCityState != null
                ) {
                    givingCityState.cityStateFunctions.giveGreatPersonToPatron(civInfo)
                    civInfo.flagsCountdown[flag] = civInfo.cityStateFunctions.turnsForGreatPersonFromCityState()
                }

                continue
            }

            if (civInfo.flagsCountdown[flag]!! > 0)
                civInfo.flagsCountdown[flag] = civInfo.flagsCountdown[flag]!! - 1

            if (civInfo.flagsCountdown[flag] != 0) continue

            when (flag) {
                CivFlags.RevoltSpawning.name -> doRevoltSpawn()
            }
        }
        handleDiplomaticVictoryFlags()
    }

    private fun handleDiplomaticVictoryFlags() {
        if (civInfo.flagsCountdown[CivFlags.ShouldResetDiplomaticVotes.name] == 0) {
            civInfo.gameInfo.diplomaticVictoryVotesCast.clear()
            civInfo.removeFlag(CivFlags.ShowDiplomaticVotingResults.name)
            civInfo.removeFlag(CivFlags.ShouldResetDiplomaticVotes.name)
        }

        if (civInfo.flagsCountdown[CivFlags.ShowDiplomaticVotingResults.name] == 0) {
            civInfo.gameInfo.processDiplomaticVictory()
            if (civInfo.gameInfo.civilizations.any { it.victoryManager.hasWon() } ) {
                civInfo.removeFlag(CivFlags.TurnsTillNextDiplomaticVote.name)
            } else {
                civInfo.addFlag(CivFlags.ShouldResetDiplomaticVotes.name, 1)
                civInfo.addFlag(CivFlags.TurnsTillNextDiplomaticVote.name, civInfo.getTurnsBetweenDiplomaticVotes())
            }
        }

        if (civInfo.flagsCountdown[CivFlags.TurnsTillNextDiplomaticVote.name] == 0) {
            civInfo.addFlag(CivFlags.ShowDiplomaticVotingResults.name, 1)
        }
    }


    private fun updateRevolts() {
        if (civInfo.gameInfo.civilizations.none { it.isBarbarian() }) {
            // Can't spawn revolts without barbarians ¯\_(ツ)_/¯
            return
        }

        if (!civInfo.hasUnique(UniqueType.SpawnRebels)) {
            civInfo.removeFlag(CivFlags.RevoltSpawning.name)
            return
        }

        if (!civInfo.hasFlag(CivFlags.RevoltSpawning.name)) {
            civInfo.addFlag(CivFlags.RevoltSpawning.name, max(getTurnsBeforeRevolt(),1))
            return
        }
    }

    private fun doRevoltSpawn() {
        val barbarians = try {
            // The first test in `updateRevolts` should prevent getting here in a no-barbarians game, but it has been shown to still occur
            civInfo.gameInfo.getBarbarianCivilization()
        } catch (ex: NoSuchElementException) {
            civInfo.removeFlag(CivFlags.RevoltSpawning.name)
            return
        }

        val random = Random()
        val rebelCount = 1 + random.nextInt(100 + 20 * (civInfo.cities.size - 1)) / 100
        val spawnCity = civInfo.cities.maxByOrNull { random.nextInt(it.population.population + 10) } ?: return
        val spawnTile = spawnCity.getTiles().maxByOrNull { rateTileForRevoltSpawn(it) } ?: return
        val unitToSpawn = civInfo.gameInfo.ruleset.units.values.asSequence().filter {
            it.uniqueTo == null && it.isMelee() && it.isLandUnit()
                    && !it.hasUnique(UniqueType.CannotAttack) && it.isBuildable(civInfo)
        }.maxByOrNull {
            random.nextInt(1000)
        } ?: return

        repeat(rebelCount) {
            civInfo.gameInfo.tileMap.placeUnitNearTile(
                spawnTile.position,
                unitToSpawn.name,
                barbarians
            )
        }

        // Will be automatically added again as long as unhappiness is still low enough
        civInfo.removeFlag(CivFlags.RevoltSpawning.name)

        civInfo.addNotification("Your citizens are revolting due to very high unhappiness!", spawnTile.position, NotificationCategory.General, unitToSpawn.name, "StatIcons/Malcontent")
    }

    // Higher is better
    private fun rateTileForRevoltSpawn(tile: Tile): Int {
        if (tile.isWater || tile.militaryUnit != null || tile.civilianUnit != null || tile.isCityCenter() || tile.isImpassible())
            return -1
        var score = 10
        if (tile.improvement == null) {
            score += 4
            if (tile.resource != null) {
                score += 3
            }
        }
        if (tile.getDefensiveBonus() > 0)
            score += 4
        return score
    }

    private fun getTurnsBeforeRevolt() =
            ((4 + Random().nextInt(3)) * max(civInfo.gameInfo.speed.modifier, 1f)).toInt()


    fun endTurn() {
        val notificationsLog = civInfo.notificationsLog
        val notificationsThisTurn = Civilization.NotificationsLog(civInfo.gameInfo.turns)
        notificationsThisTurn.notifications.addAll(civInfo.notifications)

        while (notificationsLog.size >= UncivGame.Current.settings.notificationsLogMaxTurns) {
            notificationsLog.removeFirst()
        }

        if (notificationsThisTurn.notifications.isNotEmpty())
            notificationsLog.add(notificationsThisTurn)

        civInfo.notifications.clear()

        civInfo.updateStatsForNextTurn()

        val nextTurnStats = civInfo.stats.statsForNextTurn

        civInfo.policies.endTurn(nextTurnStats.culture.toInt())
        civInfo.totalCultureForContests += nextTurnStats.culture.toInt()

        if (civInfo.isCityState())
            civInfo.questManager.endTurn()

        // disband units until there are none left OR the gold values are normal
        if (!civInfo.isBarbarian() && civInfo.gold < -100 && nextTurnStats.gold.toInt() < 0) {
            for (i in 1 until (civInfo.gold / -100)) {
                var civMilitaryUnits = civInfo.units.getCivUnits().filter { it.baseUnit.isMilitary() }
                if (civMilitaryUnits.any()) {
                    val unitToDisband = civMilitaryUnits.first()
                    unitToDisband.disband()
                    civMilitaryUnits -= unitToDisband
                    val unitName = unitToDisband.shortDisplayName()
                    civInfo.addNotification("Cannot provide unit upkeep for $unitName - unit has been disbanded!", NotificationCategory.Units, unitName, NotificationIcon.Death)
                }
            }
        }

        civInfo.addGold( nextTurnStats.gold.toInt() )

        if (civInfo.cities.isNotEmpty() && civInfo.gameInfo.ruleset.technologies.isNotEmpty())
            civInfo.tech.endTurn(nextTurnStats.science.toInt())

        civInfo.religionManager.endTurn(nextTurnStats.faith.toInt())
        civInfo.totalFaithForContests += nextTurnStats.faith.toInt()

        civInfo.espionageManager.endTurn()

        if (civInfo.isMajorCiv()) // City-states don't get great people!
            civInfo.greatPeople.addGreatPersonPoints()

        // To handle tile's owner issue (#8246), we need to run being razed city first.
        for (city in sequence {
            yieldAll(civInfo.cities.filter { it.isBeingRazed })
            yieldAll(civInfo.cities.filterNot { it.isBeingRazed })
        }.toList()) { // a city can be removed while iterating (if it's being razed) so we need to iterate over a copy
            CityTurnManager(city).endTurn()
        }

        civInfo.temporaryUniques.endTurn()

        civInfo.goldenAges.endTurn(civInfo.getHappiness())
        civInfo.units.getCivUnits().forEach { UnitTurnManager(it).endTurn() }  // This is the most expensive part of endTurn
        civInfo.diplomacy.values.toList().forEach { it.nextTurn() } // we copy the diplomacy values so if it changes in-loop we won't crash
        civInfo.cache.updateHasActiveEnemyMovementPenalty()

        civInfo.cachedMilitaryMight = -1    // Reset so we don't use a value from a previous turn

        updateWinningCiv() // Maybe we did something this turn to win
    }

    private fun updateWinningCiv(){
        if (civInfo.gameInfo.victoryData != null) return // Game already won

        val victoryType = civInfo.victoryManager.getVictoryTypeAchieved()
        if (victoryType != null) {
            civInfo.gameInfo.victoryData =
                    VictoryData(civInfo.civName, victoryType, civInfo.gameInfo.turns)

            for (civInfo in civInfo.gameInfo.civilizations)
                civInfo.popupAlerts.add(PopupAlert(AlertType.GameHasBeenWon, civInfo.civName))
        }
    }

    fun automateTurn() {
        // Defeated civs do nothing
        if (civInfo.isDefeated())
            return

        // Do stuff
        NextTurnAutomation.automateCivMoves(civInfo)

        // Update barbarian camps
        if (civInfo.isBarbarian() && !civInfo.gameInfo.gameParameters.noBarbarians)
            civInfo.gameInfo.barbarians.updateEncampments()
    }

}
