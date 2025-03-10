package com.unciv.logic.map.mapunit

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.models.ruleset.unique.StateForConditionals
import com.unciv.models.ruleset.unique.UniqueTarget
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.Promotion

class UnitPromotions : IsPartOfGameInfoSerialization {
    // Having this as mandatory constructor parameter would be safer, but this class is part of a
    // saved game and as usual the json deserializer needs a default constructor.
    // Initialization occurs in setTransients() - called as part of MapUnit.setTransients,
    // or copied in clone() as part of the UnitAction `Upgrade`.
    @Transient
    private lateinit var unit: MapUnit

    /** Experience this unit has accumulated on top of the last promotion */
    @Suppress("PropertyName")
    var XP = 0

    /** The _names_ of the promotions this unit has acquired - see [getPromotions] for object access */
    var promotions = HashSet<String>()
        private set

    // some promotions don't come from being promoted but from other things,
    // like from being constructed in a specific city etc.
    /** The number of times this unit has been promoted using experience, not counting free promotions */
    var numberOfPromotions = 0

    /** Gets this unit's promotions as objects.
     *  @param sorted if `true` return the promotions in json order (`false` gives hashset order) for display.
     *  @return a Sequence of this unit's promotions
     */
    fun getPromotions(sorted: Boolean = false): Sequence<Promotion> = sequence {
        if (promotions.isEmpty()) return@sequence
        val unitPromotions = unit.civ.gameInfo.ruleset.unitPromotions
        if (sorted && promotions.size > 1) {
            for (promotion in unitPromotions.values)
                if (promotion.name in promotions) yield(promotion)
        } else {
            for (name in promotions)
                yield(unitPromotions[name] ?: continue)
        }
    }

    fun setTransients(unit: MapUnit) {
        this.unit = unit
    }

    /** @return the XP points needed to "buy" the next promotion. 10, 30, 60, 100, 150,... */
    fun xpForNextPromotion() = (numberOfPromotions + 1) * 10

    /** @return Total XP including that already "spent" on promotions */
    fun totalXpProduced() = XP + (numberOfPromotions * (numberOfPromotions + 1)) * 5

    fun canBePromoted(): Boolean {
        if (XP < xpForNextPromotion()) return false
        if (getAvailablePromotions().none()) return false
        return true
    }

    fun addPromotion(promotionName: String, isFree: Boolean = false){
        if (!isFree) {
            XP -= xpForNextPromotion()
            numberOfPromotions++

            for (unique in unit.getTriggeredUniques(UniqueType.TriggerUponPromotion))
                UniqueTriggerActivation.triggerUnitwideUnique(unique, unit)
        }

        val ruleset = unit.civ.gameInfo.ruleset
        val promotion = ruleset.unitPromotions[promotionName]!!

        if (!promotion.hasUnique(UniqueType.SkipPromotion))
            promotions.add(promotionName)

        // If we upgrade this unit to its new version, we already need to have this promotion added,
        // so this has to go after the `promotions.add(promotionname)` line.
        doDirectPromotionEffects(promotion)

        unit.updateUniques()

        // Since some units get promotions upon construction, they will get the addPromotion from the unit.postBuildEvent
        // upon creation, BEFORE they are assigned to a tile, so the updateVisibleTiles() would crash.
        // So, if the addPromotion was triggered from there, simply don't update
        unit.updateVisibleTiles()  // some promotions/uniques give the unit bonus sight
    }

    private fun doDirectPromotionEffects(promotion: Promotion) {
        for (unique in promotion.uniqueObjects)
            if (unique.conditionalsApply(StateForConditionals(civInfo = unit.civ, unit = unit))
                    && unique.conditionals.none { it.type?.targetTypes?.contains(UniqueTarget.TriggerCondition) == true })
                UniqueTriggerActivation.triggerUnitwideUnique(unique, unit, "due to our [${unit.name}] being promoted")
    }

    /** Gets all promotions this unit could currently "buy" with enough [XP]
     *  Checks unit type, already acquired promotions, prerequisites and incompatibility uniques.
     */
    fun getAvailablePromotions(): Sequence<Promotion> {
        return unit.civ.gameInfo.ruleset.unitPromotions.values
            .asSequence()
            .filter { unit.type.name in it.unitTypes && it.name !in promotions }
            .filter { it.prerequisites.isEmpty() || it.prerequisites.any { p->p in promotions } }
            .filter { promotion -> promotion.uniqueObjects
                .none { it.type == UniqueType.OnlyAvailableWhen
                        && !it.conditionalsApply(StateForConditionals(unit.civ, unit = unit))  }
            }
    }

    fun clone(): UnitPromotions {
        val toReturn = UnitPromotions()
        toReturn.XP = XP
        toReturn.promotions.addAll(promotions)
        toReturn.numberOfPromotions = numberOfPromotions
        toReturn.unit = unit
        return toReturn
    }
}
