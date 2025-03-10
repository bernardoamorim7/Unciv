package com.unciv.logic.civilization

import com.badlogic.gdx.math.Vector2
import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.json.HashMapVector2
import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.UncivShowableException
import com.unciv.logic.automation.ai.TacticalAI
import com.unciv.logic.automation.unit.WorkerAutomation
import com.unciv.logic.city.City
import com.unciv.logic.city.managers.CityFounder
import com.unciv.logic.civilization.diplomacy.CityStateFunctions
import com.unciv.logic.civilization.diplomacy.CityStatePersonality
import com.unciv.logic.civilization.diplomacy.DiplomacyFunctions
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.civilization.managers.EspionageManager
import com.unciv.logic.civilization.managers.GoldenAgeManager
import com.unciv.logic.civilization.managers.GreatPersonManager
import com.unciv.logic.civilization.managers.PolicyManager
import com.unciv.logic.civilization.managers.QuestManager
import com.unciv.logic.civilization.managers.ReligionManager
import com.unciv.logic.civilization.managers.RuinsManager
import com.unciv.logic.civilization.managers.TechManager
import com.unciv.logic.civilization.managers.UnitManager
import com.unciv.logic.civilization.managers.VictoryManager
import com.unciv.logic.civilization.transients.CivInfoStatsForNextTurn
import com.unciv.logic.civilization.transients.CivInfoTransientCache
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.Victory
import com.unciv.models.ruleset.nation.CityStateType
import com.unciv.models.ruleset.nation.Difficulty
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.tech.Era
import com.unciv.models.ruleset.tile.ResourceSupplyList
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.StateForConditionals
import com.unciv.models.ruleset.unique.TemporaryUnique
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unique.getMatchingUniques
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toPercent
import com.unciv.ui.screens.victoryscreen.RankingType
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Proximity : IsPartOfGameInfoSerialization {
    None, // ie no cities
    Neighbors,
    Close,
    Far,
    Distant
}

class Civilization : IsPartOfGameInfoSerialization {

    @Transient
    private var workerAutomationCache: WorkerAutomation? = null
    /** Returns an instance of WorkerAutomation valid for the duration of the current turn
     * This instance carries cached data common for all Workers of this civ */
    fun getWorkerAutomation(): WorkerAutomation {
        val currentTurn = if (UncivGame.Current.isInitialized && UncivGame.Current.gameInfo != null) {
            UncivGame.Current.gameInfo!!.turns
        } else 0
        if (workerAutomationCache == null || workerAutomationCache!!.cachedForTurn != currentTurn)
            workerAutomationCache = WorkerAutomation(this, currentTurn)
        return workerAutomationCache!!
    }

    @Transient
    lateinit var gameInfo: GameInfo

    @Transient
    lateinit var nation: Nation

    @Transient
    val units = UnitManager(this)

    @Transient
    var diplomacyFunctions = DiplomacyFunctions(this)

    @Transient
    var viewableTiles = setOf<Tile>()

    @Transient
    var viewableInvisibleUnitsTiles = setOf<Tile>()

    /** This is for performance since every movement calculation depends on this, see MapUnit comment */
    @Transient
    var hasActiveEnemyMovementPenalty = false

    /** Same as above variable */
    @Transient
    var enemyMovementPenaltyUniques: Sequence<Unique>? = null

    @Transient
    var detailedCivResources = ResourceSupplyList()

    @Transient
    var summarizedCivResources = ResourceSupplyList()

    @Transient
    val cityStateFunctions = CityStateFunctions(this)

    @Transient
    var cachedMilitaryMight = -1

    @Transient
    var passThroughImpassableUnlocked = false   // Cached Boolean equal to passableImpassables.isNotEmpty()

    @Transient
    var nonStandardTerrainDamage = false


    @Transient
    var thingsToFocusOnForVictory = setOf<Victory.Focus>()

    @Transient
    var neutralRoads = HashSet<Vector2>()

    var playerType = PlayerType.AI

    /** Used in online multiplayer for human players */
    var playerId = ""
    /** The Civ's gold reserves. Public get, private set - please use [addGold] method to modify. */
    var gold = 0
        private set
    var civName = ""
    var tech = TechManager()
    var policies = PolicyManager()
    var civConstructions = CivConstructions()
    var questManager = QuestManager()
    var religionManager = ReligionManager()
    var goldenAges = GoldenAgeManager()
    var greatPeople = GreatPersonManager()
    var espionageManager = EspionageManager()
    var victoryManager = VictoryManager()
    var ruinsManager = RuinsManager()
    var diplomacy = HashMap<String, DiplomacyManager>()
    var proximity = HashMap<String, Proximity>()
    val popupAlerts = ArrayList<PopupAlert>()
    private var allyCivName: String? = null
    var naturalWonders = ArrayList<String>()

    /* AI section */
    val tacticalAI = TacticalAI()

    var notifications = ArrayList<Notification>()

    var notificationsLog = ArrayList<NotificationsLog>()
    class NotificationsLog(val turn: Int = 0) {
        var notifications = ArrayList<Notification>()
    }

    /** for trades here, ourOffers is the current civ's offers, and theirOffers is what the requesting civ offers  */
    val tradeRequests = ArrayList<TradeRequest>()

    /** See DiplomacyManager.flagsCountdown for why this does not map Enums to ints */
    var flagsCountdown = HashMap<String, Int>()

    /** Arraylist instead of HashMap as the same unique might appear multiple times
     * We don't use pairs, as these cannot be serialized due to having no no-arg constructor
     * We ALSO can't use a class inheriting from ArrayList<TemporaryUnique>() because ANNOYINGLY that doesn't pass deserialization
     * So we fake it with extension functions in Unique.kt
     *
     * This can also contain NON-temporary uniques but I can't be bothered to do the deprecation dance with this one
     */
    val temporaryUniques = ArrayList<TemporaryUnique>()

    // if we only use lists, and change the list each time the cities are changed,
    // we won't get concurrent modification exceptions.
    // This is basically a way to ensure our lists are immutable.
    var cities = listOf<City>()
    var citiesCreated = 0

    // Limit camera within explored region
    var exploredRegion = ExploredRegion()

    fun hasExplored(tile: Tile) = tile.isExplored(this)

    var lastSeenImprovement = HashMapVector2<String>()

    // To correctly determine "game over" condition as clarified in #4707
    // Nullable type meant to be deprecated and converted to non-nullable,
    // default false once we no longer want legacy save-game compatibility
    // This parameter means they owned THEIR OWN capital btw, not other civs'.
    var hasEverOwnedOriginalCapital: Boolean? = null

    val passableImpassables = HashSet<String>() // For Carthage-like uniques

    // For Aggressor, Warmonger status
    internal var numMinorCivsAttacked = 0

    var totalCultureForContests = 0
    var totalFaithForContests = 0

    /**
     * Container class to represent a historical attack recently performed by this civilization.
     *
     * @property attackingUnit Name key of [BaseUnit] type that performed the attack, or null (E.G. for city bombardments).
     * @property source Position of the tile from which the attack was made.
     * @property target Position of the tile targeted by the attack.
     * @see [MapUnit.UnitMovementMemory], [attacksSinceTurnStart]
     */
    class HistoricalAttackMemory() : IsPartOfGameInfoSerialization {
        constructor(attackingUnit: String?, source: Vector2, target: Vector2): this() {
            this.attackingUnit = attackingUnit
            this.source = source
            this.target = target
        }
        var attackingUnit: String? = null
        lateinit var source: Vector2
        lateinit var target: Vector2
        fun clone() = HistoricalAttackMemory(attackingUnit, Vector2(source), Vector2(target))
    }
    /** Deep clone an ArrayList of [HistoricalAttackMemory]s. */
    private fun ArrayList<HistoricalAttackMemory>.copy() = ArrayList(this.map { it.clone() })
    /**
     * List of attacks that this civilization has performed since the start of its most recent turn. Does not include attacks already tracked in [MapUnit.attacksSinceTurnStart] of living units. Used in movement arrow overlay.
     * @see [MapUnit.attacksSinceTurnStart]
     */
    var attacksSinceTurnStart = ArrayList<HistoricalAttackMemory>()

    var hasMovedAutomatedUnits = false

    @Transient
    var hasLongCountDisplayUnique = false

    constructor()

    constructor(civName: String) {
        this.civName = civName
    }

    fun clone(): Civilization {
        val toReturn = Civilization()
        toReturn.gold = gold
        toReturn.playerType = playerType
        toReturn.playerId = playerId
        toReturn.civName = civName
        toReturn.tech = tech.clone()
        toReturn.policies = policies.clone()
        toReturn.civConstructions = civConstructions.clone()
        toReturn.religionManager = religionManager.clone()
        toReturn.questManager = questManager.clone()
        toReturn.goldenAges = goldenAges.clone()
        toReturn.greatPeople = greatPeople.clone()
        toReturn.ruinsManager = ruinsManager.clone()
        toReturn.espionageManager = espionageManager.clone()
        toReturn.victoryManager = victoryManager.clone()
        toReturn.allyCivName = allyCivName
        for (diplomacyManager in diplomacy.values.map { it.clone() })
            toReturn.diplomacy[diplomacyManager.otherCivName] = diplomacyManager
        toReturn.proximity.putAll(proximity)
        toReturn.cities = cities.map { it.clone() }
        toReturn.neutralRoads = neutralRoads
        toReturn.exploredRegion = exploredRegion.clone()
        toReturn.lastSeenImprovement.putAll(lastSeenImprovement)
        toReturn.notifications.addAll(notifications)
        toReturn.notificationsLog.addAll(notificationsLog)
        toReturn.citiesCreated = citiesCreated
        toReturn.popupAlerts.addAll(popupAlerts)
        toReturn.tradeRequests.addAll(tradeRequests)
        toReturn.naturalWonders.addAll(naturalWonders)
        toReturn.cityStatePersonality = cityStatePersonality
        toReturn.cityStateResource = cityStateResource
        toReturn.cityStateUniqueUnit = cityStateUniqueUnit
        toReturn.flagsCountdown.putAll(flagsCountdown)
        toReturn.temporaryUniques.addAll(temporaryUniques)
        toReturn.hasEverOwnedOriginalCapital = hasEverOwnedOriginalCapital
        toReturn.passableImpassables.addAll(passableImpassables)
        toReturn.numMinorCivsAttacked = numMinorCivsAttacked
        toReturn.totalCultureForContests = totalCultureForContests
        toReturn.totalFaithForContests = totalFaithForContests
        toReturn.attacksSinceTurnStart = attacksSinceTurnStart.copy()
        toReturn.hasMovedAutomatedUnits = hasMovedAutomatedUnits
        return toReturn
    }



    //region pure functions
    fun getDifficulty(): Difficulty {
        if (isHuman()) return gameInfo.getDifficulty()
        // TODO We should be able to mark a difficulty as 'default AI difficulty' somehow
        val chieftainDifficulty = gameInfo.ruleset.difficulties["Chieftain"]
        if (chieftainDifficulty != null) return chieftainDifficulty
        return gameInfo.ruleset.difficulties.values.first()
    }

    fun getDiplomacyManager(civInfo: Civilization) = getDiplomacyManager(civInfo.civName)
    fun getDiplomacyManager(civName: String) = diplomacy[civName]!!

    fun getProximity(civInfo: Civilization) = getProximity(civInfo.civName)
    @Suppress("MemberVisibilityCanBePrivate")  // same visibility for overloads
    fun getProximity(civName: String) = proximity[civName] ?: Proximity.None

    /** Returns only undefeated civs, aka the ones we care about */
    fun getKnownCivs() = diplomacy.values.map { it.otherCiv() }.filter { !it.isDefeated() }
    fun knows(otherCivName: String) = diplomacy.containsKey(otherCivName)
    fun knows(otherCiv: Civilization) = knows(otherCiv.civName)

    fun getCapital() = cities.firstOrNull { it.isCapital() }
    fun isHuman() = playerType == PlayerType.Human
    fun isAI() = playerType == PlayerType.AI
    fun isOneCityChallenger() = playerType == PlayerType.Human && gameInfo.gameParameters.oneCityChallenge

    fun isCurrentPlayer() = gameInfo.currentPlayerCiv == this
    fun isMajorCiv() = nation.isMajorCiv()
    fun isMinorCiv() = nation.isCityState() || nation.isBarbarian()
    fun isCityState(): Boolean = nation.isCityState()
    fun isBarbarian() = nation.isBarbarian()
    fun isSpectator() = nation.isSpectator()
    fun isAlive(): Boolean = !isDefeated()

    @delegate:Transient
    val cityStateType: CityStateType by lazy { gameInfo.ruleset.cityStateTypes[nation.cityStateType!!]!! }
    var cityStatePersonality: CityStatePersonality = CityStatePersonality.Neutral
    var cityStateResource: String? = null
    var cityStateUniqueUnit: String? = null // Unique unit for militaristic city state. Might still be null if there are no appropriate units


    fun hasMetCivTerritory(otherCiv: Civilization): Boolean =
            otherCiv.getCivTerritory().any { gameInfo.tileMap[it].isExplored(this) }
    fun getCompletedPolicyBranchesCount(): Int = policies.adoptedPolicies.count { Policy.isBranchCompleteByName(it) }
    fun originalMajorCapitalsOwned(): Int = cities.count { it.isOriginalCapital && it.foundingCiv != "" && gameInfo.getCivilization(it.foundingCiv).isMajorCiv() }
    private fun getCivTerritory() = cities.asSequence().flatMap { it.tiles.asSequence() }

    fun getPreferredVictoryType(): String {
        val victoryTypes = gameInfo.gameParameters.victoryTypes
        if (victoryTypes.size == 1)
            return victoryTypes.first() // That is the most relevant one
        val victoryType = nation.preferredVictoryType
        return if (victoryType in gameInfo.ruleset.victories) victoryType
               else Constants.neutralVictoryType
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun getPreferredVictoryTypeObject(): Victory? {
        val preferredVictoryType = getPreferredVictoryType()
        return if (preferredVictoryType == Constants.neutralVictoryType) null
               else gameInfo.ruleset.victories[getPreferredVictoryType()]!!
    }

    fun wantsToFocusOn(focus: Victory.Focus): Boolean {
        return thingsToFocusOnForVictory.contains(focus)
    }

    @Transient
    val stats = CivInfoStatsForNextTurn(this)

    @Transient
    val cache = CivInfoTransientCache(this)

    fun updateStatsForNextTurn() {
        stats.happiness = stats.getHappinessBreakdown().values.sum().roundToInt()
        stats.statsForNextTurn = stats.getStatMapForNextTurn().values.reduce { a, b -> a + b }
    }

    fun getHappiness() = stats.happiness

    fun getCivResources(): ResourceSupplyList = summarizedCivResources

    // Preserves some origins for resources so we can separate them for trades
    fun getCivResourcesWithOriginsForTrade(): ResourceSupplyList {
        val newResourceSupplyList = ResourceSupplyList(keepZeroAmounts = true)
        for (resourceSupply in detailedCivResources) {
            // If we got it from another trade or from a CS, preserve the origin
            if (resourceSupply.isCityStateOrTradeOrigin()) {
                newResourceSupplyList.add(resourceSupply.copy())
                newResourceSupplyList.add(resourceSupply.resource, Constants.tradable, 0) // Still add an empty "tradable" entry so it shows up in the list
            }
            else
                newResourceSupplyList.add(resourceSupply.resource, Constants.tradable, resourceSupply.amount)
        }
        return newResourceSupplyList
    }

    fun isCapitalConnectedToCity(city: City): Boolean = cache.citiesConnectedToCapitalToMediums.keys.contains(city)


    /**
     * Returns a dictionary of ALL resource names, and the amount that the civ has of each
     */
    fun getCivResourcesByName(): HashMap<String, Int> {
        val hashMap = HashMap<String, Int>(gameInfo.ruleset.tileResources.size)
        for (resource in gameInfo.ruleset.tileResources.keys) hashMap[resource] = 0
        for (entry in getCivResources())
            hashMap[entry.resource.name] = entry.amount
        return hashMap
    }

    fun getResourceModifier(resource: TileResource): Float {
        var resourceModifier = 1f
        for (unique in getMatchingUniques(UniqueType.DoubleResourceProduced))
            if (unique.params[0] == resource.name)
                resourceModifier *= 2f
        if (resource.resourceType == ResourceType.Strategic) {
            resourceModifier *= 1f + getMatchingUniques(UniqueType.StrategicResourcesIncrease)
                .map { it.params[0].toFloat() / 100f }.sum()

        }
        return resourceModifier
    }

    fun hasResource(resourceName: String): Boolean = getCivResourcesByName()[resourceName]!! > 0

    fun hasUnique(uniqueType: UniqueType, stateForConditionals: StateForConditionals =
        StateForConditionals(this)) = getMatchingUniques(uniqueType, stateForConditionals).any()

    // Does not return local uniques, only global ones.
    /** Destined to replace getMatchingUniques, gradually, as we fill the enum */
    fun getMatchingUniques(uniqueType: UniqueType, stateForConditionals: StateForConditionals = StateForConditionals(this), cityToIgnore: City? = null) = sequence {
        yieldAll(nation.getMatchingUniques(uniqueType, stateForConditionals))
        yieldAll(cities.asSequence()
            .filter { it != cityToIgnore }
            .flatMap { city -> city.getMatchingUniquesWithNonLocalEffects(uniqueType, stateForConditionals) }
        )
        yieldAll(policies.policyUniques.getMatchingUniques(uniqueType, stateForConditionals))
        yieldAll(tech.techUniques.getMatchingUniques(uniqueType, stateForConditionals))
        yieldAll(temporaryUniques.getMatchingUniques(uniqueType, stateForConditionals))
        yieldAll(getEra().getMatchingUniques(uniqueType, stateForConditionals))
        yieldAll(cityStateFunctions.getUniquesProvidedByCityStates(uniqueType, stateForConditionals))
        if (religionManager.religion != null)
            yieldAll(religionManager.religion!!.getFounderUniques()
                .filter { it.isOfType(uniqueType) && it.conditionalsApply(stateForConditionals) })

        yieldAll(getCivResources().asSequence()
            .filter { it.amount > 0 }
            .flatMap { it.resource.getMatchingUniques(uniqueType, stateForConditionals) }
        )

        yieldAll(gameInfo.ruleset.globalUniques.getMatchingUniques(uniqueType, stateForConditionals))
    }

    fun getTriggeredUniques(trigger: UniqueType, stateForConditionals: StateForConditionals = StateForConditionals(this)) : Sequence<Unique> = sequence{
        yieldAll(nation.uniqueMap.getTriggeredUniques(trigger, stateForConditionals))
        yieldAll(cities.asSequence()
            .flatMap { city -> city.cityConstructions.builtBuildingUniqueMap.getTriggeredUniques(trigger, stateForConditionals) }
        )
        yieldAll(policies.policyUniques.getTriggeredUniques(trigger, stateForConditionals))
        yieldAll(tech.techUniques.getTriggeredUniques(trigger, stateForConditionals))
        yieldAll(getEra().uniqueMap.getTriggeredUniques (trigger, stateForConditionals))
        yieldAll(gameInfo.ruleset.globalUniques.uniqueMap.getTriggeredUniques(trigger, stateForConditionals))
    }


    fun shouldOpenTechPicker(): Boolean {
        if (!tech.canResearchTech()) return false
        if (tech.freeTechs != 0) return true
        return tech.currentTechnology() == null && cities.isNotEmpty()
    }

    fun getEquivalentBuilding(buildingName: String) = getEquivalentBuilding(gameInfo.ruleset.buildings[buildingName]!!)
    fun getEquivalentBuilding(baseBuilding: Building): Building {
        if (baseBuilding.replaces != null)
            return getEquivalentBuilding(baseBuilding.replaces!!)

        for (building in cache.uniqueBuildings)
            if (building.replaces == baseBuilding.name)
                return building
        return baseBuilding
    }

    fun getEquivalentUnit(baseUnitName: String): BaseUnit {
        val baseUnit = gameInfo.ruleset.units[baseUnitName]
            ?: throw UncivShowableException("Unit $baseUnitName doesn't seem to exist!")
        return getEquivalentUnit(baseUnit)
    }

    fun getEquivalentUnit(baseUnit: BaseUnit): BaseUnit {
        if (baseUnit.replaces != null)
            return getEquivalentUnit(baseUnit.replaces!!) // Equivalent of unique unit is the equivalent of the replaced unit

        for (unit in cache.uniqueUnits)
            if (unit.replaces == baseUnit.name)
                return unit
        return baseUnit
    }

    override fun toString(): String = civName // for debug

    /**
     *  Determine loss conditions.
     *
     *  If the civ has never controlled an original capital, it stays 'alive' as long as it has units (irrespective of non-original-capitals owned)
     *  Otherwise, it stays 'alive' as long as it has cities (irrespective of settlers owned)
     */
    fun isDefeated() = when {
        isBarbarian() || isSpectator() -> false     // Barbarians and voyeurs can't lose
        hasEverOwnedOriginalCapital == true -> cities.isEmpty()
        else -> units.getCivUnits().none()
    }

    fun getEra(): Era = tech.era

    fun getEraNumber(): Int = getEra().eraNumber

    fun isAtWarWith(otherCiv: Civilization) = diplomacyFunctions.isAtWarWith(otherCiv)

    fun isAtWar() = diplomacy.values.any { it.diplomaticStatus == DiplomaticStatus.War && !it.otherCiv().isDefeated() }


    /**
     * Returns a civilization caption suitable for greetings including player type info:
     * Like "Milan" if the nation is a city state, "Caesar of Rome" otherwise, with an added
     * " (AI)", " (Human - Hotseat)", or " (Human - Multiplayer)" if the game is multiplayer.
     */
    fun getLeaderDisplayName(): String {
        val severalHumans = gameInfo.civilizations.count { it.playerType == PlayerType.Human } > 1
        val online = gameInfo.gameParameters.isOnlineMultiplayer
        return nation.getLeaderDisplayName().tr() +
            when {
                !online && !severalHumans -> ""  // offline single player will know everybody else is AI
                playerType == PlayerType.AI -> " (${"AI".tr()})"
                online -> " (${"Human".tr()} - ${"Multiplayer".tr()})"
                else -> " (${"Human".tr()} - ${"Hotseat".tr()})"
            }
    }


    fun getStatForRanking(category: RankingType): Int {
        return if (isDefeated()) 0
        else when (category) {
                RankingType.Score -> calculateTotalScore().toInt()
                RankingType.Population -> cities.sumOf { it.population.population }
                RankingType.Crop_Yield -> stats.statsForNextTurn.food.roundToInt()
                RankingType.Production -> stats.statsForNextTurn.production.roundToInt()
                RankingType.Gold -> gold
                RankingType.Territory -> cities.sumOf { it.tiles.size }
                RankingType.Force -> getMilitaryMight()
                RankingType.Happiness -> getHappiness()
                RankingType.Technologies -> tech.researchedTechnologies.size
                RankingType.Culture -> policies.adoptedPolicies.count { !Policy.isBranchCompleteByName(it) }
        }
    }

    private fun getMilitaryMight(): Int {
        if (cachedMilitaryMight < 0)
            cachedMilitaryMight = calculateMilitaryMight()
        return  cachedMilitaryMight
    }

    private fun calculateMilitaryMight(): Int {
        var sum = 1 // minimum value, so we never end up with 0
        for (unit in units.getCivUnits()) {
            sum += if (unit.baseUnit.isWaterUnit())
                unit.getForceEvaluation() / 2   // Really don't value water units highly
            else
                unit.getForceEvaluation()
        }
        val goldBonus = sqrt(max(0f, gold.toFloat())).toPercent()  // 2f if gold == 10000
        sum = (sum * min(goldBonus, 2f)).toInt()    // 2f is max bonus
        return sum
    }

    fun isMinorCivAggressor() = numMinorCivsAttacked >= 2
    fun isMinorCivWarmonger() = numMinorCivsAttacked >= 4

    fun isLongCountActive(): Boolean {
        val unique = getMatchingUniques(UniqueType.MayanGainGreatPerson).firstOrNull()
            ?: return false
        return tech.isResearched(unique.params[1])
    }
    fun isLongCountDisplay() = hasLongCountDisplayUnique && isLongCountActive()

    fun calculateScoreBreakdown(): HashMap<String,Double> {
        val scoreBreakdown = hashMapOf<String,Double>()
        // 1276 is the number of tiles in a medium sized map. The original uses 4160 for this,
        // but they have bigger maps
        var mapSizeModifier = 1276 / gameInfo.tileMap.mapParameters.numberOfTiles().toDouble()
        if (mapSizeModifier > 1)
            mapSizeModifier = (mapSizeModifier - 1) / 3 + 1

        scoreBreakdown["Cities"] = cities.size * 10 * mapSizeModifier
        scoreBreakdown["Population"] = cities.sumOf { it.population.population } * 3 * mapSizeModifier
        scoreBreakdown["Tiles"] = cities.sumOf { city -> city.getTiles().filter { !it.isWater}.count() } * 1 * mapSizeModifier
        scoreBreakdown["Wonders"] = 40 * cities
            .sumOf { city -> city.cityConstructions.builtBuildings
                .filter { gameInfo.ruleset.buildings[it]!!.isWonder }.size
            }.toDouble()
        scoreBreakdown["Technologies"] = tech.getNumberOfTechsResearched() * 4.toDouble()
        scoreBreakdown["Future Tech"] = tech.repeatingTechsResearched * 10.toDouble()

        return scoreBreakdown
    }

    fun calculateTotalScore() = calculateScoreBreakdown().values.sum()

    //endregion

    //region state-changing functions

    /** This is separate because the REGULAR setTransients updates the viewable ties,
     *  and updateVisibleTiles tries to meet civs...
     *  And if the civs don't yet know who they are then they don't know if they're barbarians =\
     *  */
    fun setNationTransient() {
        nation = gameInfo.ruleset.nations[civName]
                ?: throw UncivShowableException("Nation $civName is not found!")
    }

    fun setTransients() {
        goldenAges.civInfo = this
        greatPeople.civInfo = this
        civConstructions.setTransients(civInfo = this)
        policies.setTransients(this)
        questManager.setTransients(this)
        religionManager.setTransients(this) // needs to be before tech, since tech setTransients looks at all uniques
        tech.setTransients(this)
        ruinsManager.setTransients(this)
        espionageManager.setTransients(this)
        victoryManager.civInfo = this

        for (diplomacyManager in diplomacy.values) {
            diplomacyManager.civInfo = this
            diplomacyManager.updateHasOpenBorders()
        }

        for (cityInfo in cities) {
            cityInfo.setTransients(this) // must be before the city's setTransients because it depends on the tilemap, that comes from the currentPlayerCivInfo
        }

        // Now that all tile transients have been updated, clean "worked" tiles that are not under the Civ's control
        for (cityInfo in cities)
            for (workedTile in cityInfo.workedTiles.toList())
                if (gameInfo.tileMap[workedTile].getOwner() != this)
                    cityInfo.workedTiles.remove(workedTile)

        passThroughImpassableUnlocked = passableImpassables.isNotEmpty()
        // Cache whether this civ gets nonstandard terrain damage for performance reasons.
        nonStandardTerrainDamage = getMatchingUniques(UniqueType.DamagesContainingUnits)
            .any { gameInfo.ruleset.terrains[it.params[0]]!!.damagePerTurn != it.params[1].toInt() }

        hasLongCountDisplayUnique = hasUnique(UniqueType.MayanCalendarDisplay)

        tacticalAI.init(this)

        cache.setTransients()
    }


    fun addFlag(flag: String, count: Int) = flagsCountdown.set(flag, count)
    fun removeFlag(flag: String) = flagsCountdown.remove(flag)
    fun hasFlag(flag: String) = flagsCountdown.contains(flag)

    fun getTurnsBetweenDiplomaticVotes() = (15 * gameInfo.speed.modifier).toInt() // Dunno the exact calculation, hidden in Lua files
    fun getTurnsTillNextDiplomaticVote() = flagsCountdown[CivFlags.TurnsTillNextDiplomaticVote.name]

    fun getRecentBullyingCountdown() = flagsCountdown[CivFlags.RecentlyBullied.name]
    fun getTurnsTillCallForBarbHelp() = flagsCountdown[CivFlags.TurnsTillCallForBarbHelp.name]

    fun mayVoteForDiplomaticVictory() =
        getTurnsTillNextDiplomaticVote() == 0
        && civName !in gameInfo.diplomaticVictoryVotesCast.keys
        // Only vote if there is someone to vote for, may happen in one-more-turn mode
        && gameInfo.civilizations.any { it.isMajorCiv() && !it.isDefeated() && it != this }

    fun diplomaticVoteForCiv(chosenCivName: String?) {
        if (chosenCivName != null) gameInfo.diplomaticVictoryVotesCast[civName] = chosenCivName
    }

    fun shouldShowDiplomaticVotingResults() =
         flagsCountdown[CivFlags.ShowDiplomaticVotingResults.name] == 0
         && gameInfo.civilizations.any { it.isMajorCiv() && !it.isDefeated() && it != this }


    /** Modify gold by a given amount making sure it does neither overflow nor underflow.
     * @param delta the amount to add (can be negative)
     */
    fun addGold(delta: Int) {
        // not using Long.coerceIn - this stays in 32 bits
        gold = when {
            delta > 0 && gold > Int.MAX_VALUE - delta -> Int.MAX_VALUE
            delta < 0 && gold < Int.MIN_VALUE - delta -> Int.MIN_VALUE
            else -> gold + delta
        }
    }

    fun hasStatToBuy(stat: Stat, price: Int): Boolean {
        return when {
            gameInfo.gameParameters.godMode -> true
            price == 0 -> true
            else -> getStatReserve(stat) >= price
        }
    }

    fun addStats(stats: Stats){
        for ((stat, amount) in stats) addStat(stat, amount.toInt())
    }

    fun addStat(stat: Stat, amount: Int) {
        when (stat) {
            Stat.Culture -> { policies.addCulture(amount)
                              if(amount > 0) totalCultureForContests += amount }
            Stat.Science -> tech.addScience(amount)
            Stat.Gold -> addGold(amount)
            Stat.Faith -> { religionManager.storedFaith += amount
                            if(amount > 0) totalFaithForContests += amount }
            else -> {}
            // Food and Production wouldn't make sense to be added nationwide
            // Happiness cannot be added as it is recalculated again, use a unique instead
        }
    }

    fun getStatReserve(stat: Stat): Int {
        return when (stat) {
            Stat.Culture -> policies.storedCulture
            Stat.Science -> {
                if (tech.currentTechnology() == null) 0
                else tech.researchOfTech(tech.currentTechnology()!!.name)
            }
            Stat.Gold -> gold
            Stat.Faith -> religionManager.storedFaith
            else -> 0
        }
    }

    fun addNotification(text: String, location: Vector2, category:NotificationCategory, vararg notificationIcons: String) {
        addNotification(text, LocationAction(location), category, *notificationIcons)
    }

    fun addNotification(text: String, category:NotificationCategory, vararg notificationIcons: String) = addNotification(text, null, category, *notificationIcons)

    fun addNotification(text: String, action: NotificationAction?, category:NotificationCategory, vararg notificationIcons: String) {
        if (playerType == PlayerType.AI) return // no point in lengthening the saved game info if no one will read it
        val arrayList = notificationIcons.toCollection(ArrayList())
        notifications.add(Notification(text, arrayList,
                if (action is LocationAction && action.locations.isEmpty()) null else action, category))
    }

    fun addCity(location: Vector2) {
        val newCity = CityFounder().foundCity(this, location)
        newCity.cityConstructions.chooseNextConstruction()
    }

    fun destroy() {
        val destructionText = if (isMajorCiv()) "The civilization of [$civName] has been destroyed!"
        else "The City-State of [$civName] has been destroyed!"
        for (civ in gameInfo.civilizations)
            civ.addNotification(destructionText, NotificationCategory.General, civName, NotificationIcon.Death)
        units.getCivUnits().forEach { it.destroy() }
        tradeRequests.clear() // if we don't do this then there could be resources taken by "pending" trades forever
        for (diplomacyManager in diplomacy.values) {
            diplomacyManager.trades.clear()
            diplomacyManager.otherCiv().getDiplomacyManager(this).trades.clear()
            for (tradeRequest in diplomacyManager.otherCiv().tradeRequests.filter { it.requestingCiv == civName })
                diplomacyManager.otherCiv().tradeRequests.remove(tradeRequest) // it  would be really weird to get a trade request from a dead civ
        }
    }

    fun updateProximity(otherCiv: Civilization, preCalculated: Proximity? = null): Proximity = cache.updateProximity(otherCiv, preCalculated)

    /**
     * Removes current capital then moves capital to argument city if not null
     */
    fun moveCapitalTo(city: City?) {
        if (cities.isNotEmpty() && getCapital() != null) {
            val oldCapital = getCapital()!!
            oldCapital.cityConstructions.removeBuilding(oldCapital.capitalCityIndicator())
        }

        if (city == null) return // can't move a non-existent city but we can always remove our old capital
        // move new capital
        city.cityConstructions.addBuilding(city.capitalCityIndicator())
        city.isBeingRazed = false // stop razing the new capital if it was being razed
    }

    fun moveCapitalToNextLargest() {
        val availableCities = cities.filterNot { it.isCapital() }
        if (availableCities.none()) return
        var newCapital = availableCities.filterNot { it.isPuppet }.maxByOrNull { it.population.population }

        if (newCapital == null) { // No non-puppets, take largest puppet and annex
            newCapital = availableCities.maxByOrNull { it.population.population }!!
            newCapital.annexCity()
        }
        moveCapitalTo(newCapital)
    }

    fun getAllyCiv() = allyCivName
    fun setAllyCiv(newAllyName: String?) { allyCivName = newAllyName }

    fun asPreview() = CivilizationInfoPreview(this)
}

/**
 * Reduced variant of CivilizationInfo used for load preview.
 */
class CivilizationInfoPreview() {
    var civName = ""
    var playerType = PlayerType.AI
    var playerId = ""
    fun isPlayerCivilization() = playerType == PlayerType.Human

    /**
     * Converts a CivilizationInfo object (can be uninitialized) into a CivilizationInfoPreview object.
     */
    constructor(civilization: Civilization) : this() {
        civName = civilization.civName
        playerType = civilization.playerType
        playerId = civilization.playerId
    }
}

enum class CivFlags {
    CityStateGreatPersonGift,
    TurnsTillNextDiplomaticVote,
    ShowDiplomaticVotingResults,
    ShouldResetDiplomaticVotes,
    RecentlyBullied,
    TurnsTillCallForBarbHelp,
    RevoltSpawning,
}
