package com.vaan.absoluterandom

import android.os.SystemClock

enum class TarotDeck(val title: String, val description: String) {
    RWS("Rider-Waite-Smith", "Classic scenic tarot. Best general-purpose lens for symbolism, everyday situations, relationships, choices, and readable narrative."),
    THOTH("Thoth Tarot", "Dense Thelemic, astrological, Qabalistic and elemental lens. Emphasizes forces, transformation, will, polarity, initiation and occult correspondences."),
    HERMETIC("Hermetic Tarot", "Golden Dawn-style technical lens. Emphasizes astrology, Qabalah, elemental dignities and occult correspondences."),
    TABULA("Tabula Mundi", "Thoth-based lens with strong decanic, astrological and Golden Dawn structure. Readings emphasize how archetypal forces combine."),
    MARSEILLE("Tarot de Marseille", "Older continental structure. Emphasizes number, suit, visual pattern, direction, sequence and Major Arcana archetypes."),
    HAINDL("Haindl Tarot", "Mythic and cross-cultural lens with elemental, rune, astrological and spiritual-development emphasis."),
    CUSTOM("Custom Archetype Deck", "Uses your dedicated Tarot Archetypes custom bank. Each item becomes its own oracle/archetype card.")
}

data class ReadingPreset(val title: String, val description: String, val positions: List<String>)

object ReadingPresets {
    val all = listOf(
        ReadingPreset("Ask Any Question", "A flexible three-card reading for a question you type.", listOf("Core situation", "Hidden influence", "Guidance / direction")),
        ReadingPreset("One Card", "A single concentrated answer, energy, warning or message.", listOf("Core answer")),
        ReadingPreset("Past • Present • Future", "Tracks the arc from what shaped the matter, through now, into its current trajectory.", listOf("Past influence", "Present", "Future trajectory")),
        ReadingPreset("Future Path", "Looks at what is forming ahead and how your choices may shape it.", listOf("Where you are now", "What is approaching", "Hidden future influence", "Best action", "Likely direction")),
        ReadingPreset("Dating / New Love", "For meeting someone, new attraction, dating energy and relationship potential.", listOf("Your readiness", "Person / energy approaching", "How or where connection forms", "Relationship dynamic", "Potential direction")),
        ReadingPreset("Relationship", "Examines two people and the path of the relationship.", listOf("Your role", "Partner's role", "Past foundation", "Present dynamic", "Looming future")),
        ReadingPreset("Compatibility", "Compares wants, similarities, differences and three layers of compatibility.", listOf("Your wants", "Their wants", "Differences", "Similarities", "Emotional compatibility", "Physical / chemistry compatibility", "Mental compatibility")),
        ReadingPreset("Career", "Examines purpose, motivation, role, current state, reward and trajectory.", listOf("Purpose here", "What motivates you", "Your role / responsibility", "Current career state", "Potential reward", "Where it is leading")),
        ReadingPreset("Money", "A practical four-card financial reading.", listOf("Current financial situation", "Opportunity", "Risk / blind spot", "Advice")),
        ReadingPreset("Decision", "Compares two paths without pretending the choice is already fixed.", listOf("Option A energy", "Option A consequence", "Option B energy", "Option B consequence", "Deciding principle")),
        ReadingPreset("Yes / No", "Gives a directional lean plus the forces supporting and resisting it.", listOf("What supports YES", "What supports NO", "Overall answer / advice")),
        ReadingPreset("Shadow Work", "Explores a hidden pattern and how to integrate it.", listOf("Shadow showing itself", "Root / origin", "Trigger", "Lesson inside it", "Integration path")),
        ReadingPreset("Spiritual Path", "Looks at the present spiritual phase and the next developmental movement.", listOf("Current spiritual state", "Lesson", "Block", "Ally / resource", "Next step")),
        ReadingPreset("Daily Guidance", "A compact daily reading.", listOf("Energy of the day", "Challenge", "Best response")),
        ReadingPreset("Problem • Cause • Solution", "Diagnoses a specific problem in three steps.", listOf("Problem", "Underlying cause", "Solution / response")),
        ReadingPreset("Situation • Action • Outcome", "Simple tactical reading: what is happening, what to do, and where that action points.", listOf("Situation", "Action", "Outcome")),
        ReadingPreset("Celtic Cross", "Ten-card classic for a definite question, including atmosphere, obstacle, foundation, near future, attitude, environment, hopes/fears and culmination.", listOf("What covers you / atmosphere", "What crosses you / obstacle", "What crowns you / aim or best attainable", "What is beneath / foundation", "What is behind / passing influence", "What is before / near future", "Your position / attitude", "Environment / house", "Hopes or fears", "What will come / culmination")),
        ReadingPreset("Custom Spread", "Choose 1–10 positions and name them yourself.", listOf("Position 1"))
    )
}

data class TarotCard(
    val id: Int,
    val name: String,
    val arcana: String,
    val suit: String?,
    val element: String?,
    val rank: Int?,
    val court: String?,
    val upright: String,
    val reversed: String,
    val polarity: Int
)

data class DrawnCard(val card: TarotCard, val displayName: String, val reversed: Boolean, val position: String)

object TarotLibrary {
    private fun m(id:Int, name:String, up:String, rev:String, p:Int) =
        TarotCard(id, name, "Major", null, majorElement(name), null, null, up, rev, p)

    val cards: List<TarotCard> = buildList {
        add(m(0,"The Fool","new beginning, freedom, leap of faith, openness","recklessness, fear of beginning, poor preparation",1))
        add(m(1,"The Magician","will, skill, focused action, manifestation","misdirection, scattered will, manipulation, unused skill",2))
        add(m(2,"The High Priestess","intuition, hidden knowledge, inner listening","secrets, blocked intuition, avoidance of inner truth",0))
        add(m(3,"The Empress","growth, fertility, pleasure, nurture, creation","smothering, stagnation, depletion, creative block",2))
        add(m(4,"The Emperor","structure, authority, boundaries, command","rigidity, domination, weak structure, control issues",1))
        add(m(5,"The Hierophant","tradition, teaching, initiation, shared system","dogma, rebellion, unconventional path, empty ritual",0))
        add(m(6,"The Lovers","union, values, attraction, meaningful choice","misalignment, temptation, disharmony, conflicted values",2))
        add(m(7,"The Chariot","drive, discipline, victory through control","loss of direction, aggression, stalled momentum",2))
        add(m(8,"Strength","courage, self-command, instinct integrated with will","self-doubt, force without control, suppressed instinct",2))
        add(m(9,"The Hermit","solitude, wisdom, search, inner guidance","isolation, avoidance, refusing guidance, aimless withdrawal",0))
        add(m(10,"Wheel of Fortune","turning point, cycles, change, chance","resistance to change, setback, repeating cycle",1))
        add(m(11,"Justice","truth, balance, consequence, clear judgment","bias, imbalance, evasion, unfair consequence",0))
        add(m(12,"The Hanged Man","suspension, surrender, new perspective, sacrifice","stalling, martyrdom, refusal to release, pointless delay",0))
        add(m(13,"Death","ending, transformation, release, irreversible transition","clinging, stagnation, fear of ending, prolonged transition",0))
        add(m(14,"Temperance","integration, moderation, alchemy, timing","excess, imbalance, poor mixing, impatience",2))
        add(m(15,"The Devil","attachment, appetite, bondage, shadow desire","breaking chains, confronting attachment, denial of desire",-1))
        add(m(16,"The Tower","rupture, revelation, collapse of false structure","avoided crisis, internal upheaval, delayed breakdown",-2))
        add(m(17,"The Star","hope, renewal, openness, guiding vision","discouragement, disconnection, dimmed hope",2))
        add(m(18,"The Moon","uncertainty, dream, instinct, projection, hidden terrain","confusion clearing, exposed illusion, fear turned inward",-1))
        add(m(19,"The Sun","clarity, vitality, success, joy, visibility","temporary clouding, ego excess, delayed success",2))
        add(m(20,"Judgement","awakening, reckoning, calling, decisive review","self-doubt, refusal of call, unfinished reckoning",1))
        add(m(21,"The World","completion, integration, mastery, arrival","unfinished cycle, delay in completion, loose ends",2))

        val defs = mapOf(
            "Wands" to listOf(
                Triple("Ace","inspiration, ignition, new drive","false start, blocked energy, waning enthusiasm"), Triple("Two","planning, personal power, choosing direction","fear of expansion, poor planning, constrained options"), Triple("Three","expansion, foresight, momentum, results approaching","delay, limited vision, obstacles to expansion"), Triple("Four","celebration, stable base, homecoming, milestone","unstable foundation, private celebration, tension at home"), Triple("Five","competition, friction, testing strength","avoiding conflict, unresolved tension, internal struggle"), Triple("Six","victory, recognition, confidence, good news","ego wound, delayed recognition, hollow victory"), Triple("Seven","defense, conviction, holding ground","overwhelm, giving ground, defensive exhaustion"), Triple("Eight","speed, messages, rapid movement, alignment","delay, crossed signals, scattered momentum"), Triple("Nine","resilience, boundaries, last stand","fatigue, paranoia, weakened defenses"), Triple("Ten","burden, responsibility, carrying too much","release, collapse under load, refusing delegation"), Triple("Page","curiosity, message, adventurous spark","immaturity, unreliable enthusiasm, bad news"), Triple("Knight","pursuit, bold action, heat, adventure","impulsiveness, volatility, reckless pursuit"), Triple("Queen","confidence, magnetism, independence, warm command","jealousy, insecurity, demanding attention"), Triple("King","vision, leadership, enterprise, controlled fire","arrogance, domineering force, impulsive leadership")
            ),
            "Cups" to listOf(
                Triple("Ace","emotional opening, love, intuition, overflowing feeling","emotional block, emptiness, suppressed feeling"), Triple("Two","mutual attraction, partnership, exchange","imbalance, separation, miscommunication"), Triple("Three","friendship, celebration, community","overindulgence, gossip, social friction"), Triple("Four","apathy, contemplation, emotional withdrawal","renewed interest, restlessness, emerging awareness"), Triple("Five","grief, disappointment, focus on loss","acceptance, recovery, seeing what remains"), Triple("Six","memory, nostalgia, innocence, return","stuck in past, unrealistic nostalgia, outgrowing old bonds"), Triple("Seven","many options, fantasy, temptation, imagination","clarity, choosing, illusion collapsing"), Triple("Eight","walking away, seeking deeper meaning","fear of leaving, drifting back, avoidance"), Triple("Nine","satisfaction, pleasure, wish fulfilled","excess, superficial satisfaction, unmet deeper need"), Triple("Ten","emotional fulfillment, family harmony, belonging","disharmony, broken ideal, family tension"), Triple("Page","sensitive message, intuition, crush, creative feeling","emotional immaturity, blocked intuition, mixed signals"), Triple("Knight","romance, invitation, idealism, pursuit of feeling","moodiness, fantasy without follow-through, emotional manipulation"), Triple("Queen","empathy, intuition, emotional depth, receptivity","overwhelm, dependency, emotional boundary problems"), Triple("King","emotional mastery, diplomacy, compassion","emotional control games, volatility beneath calm, detachment")
            ),
            "Swords" to listOf(
                Triple("Ace","truth, breakthrough, decisive thought, clarity","confusion, misuse of truth, poor judgment"), Triple("Two","stalemate, guarded choice, weighing options","indecision breaking, information overload, avoidance exposed"), Triple("Three","heartbreak, painful truth, separation","healing, forgiveness, lingering pain"), Triple("Four","rest, recovery, strategic pause","restlessness, burnout, forced pause"), Triple("Five","conflict, hollow victory, tension, self-interest","reconciliation, resentment, aftermath of conflict"), Triple("Six","transition, moving on, passage to calmer ground","baggage, resistance to transition, unfinished departure"), Triple("Seven","strategy, secrecy, stealth, acting independently","exposure, self-deception, failed strategy"), Triple("Eight","restriction, trapped thinking, limited options","release, new perspective, reclaiming agency"), Triple("Nine","anxiety, fear, sleepless thought, guilt","recovery, facing fear, deepening distress"), Triple("Ten","painful ending, collapse, finality","survival, recovery, resisting an ending"), Triple("Page","alert mind, curiosity, observation, news","gossip, suspicion, scattered thinking, premature speech"), Triple("Knight","decisive charge, argument, speed, intellectual force","rash words, aggression, poor timing"), Triple("Queen","discernment, independence, boundaries, direct truth","bitterness, harsh judgment, isolation"), Triple("King","logic, authority of mind, strategy, law","cold control, intellectual arrogance, manipulative logic")
            ),
            "Pentacles" to listOf(
                Triple("Ace","material opportunity, health, money seed, grounded start","missed opportunity, poor foundation, scarcity thinking"), Triple("Two","juggling priorities, adaptability, resource balance","overload, disorganization, dropped priorities"), Triple("Three","teamwork, craft, learning, building quality","poor teamwork, weak standards, misaligned effort"), Triple("Four","security, holding resources, control","greed, fear of loss, loosening control"), Triple("Five","hardship, exclusion, scarcity, material stress","recovery, help arriving, improvement after hardship"), Triple("Six","giving and receiving, support, resources, power balance","strings attached, debt, unequal exchange"), Triple("Seven","assessment, patience, investment, long-term growth","impatience, poor return, abandoning investment"), Triple("Eight","practice, skill-building, disciplined craft","perfectionism, boredom, sloppy work"), Triple("Nine","self-sufficiency, earned comfort, refinement","dependence, overwork for status, unstable independence"), Triple("Ten","legacy, family wealth, long-term security","family conflict, unstable legacy, financial loss"), Triple("Page","study, practical message, new skill, opportunity","procrastination, weak planning, missed lesson"), Triple("Knight","reliability, routine, persistence, steady work","stagnation, stubbornness, dull routine"), Triple("Queen","practical care, embodied abundance, grounded support","self-neglect, possessiveness, work-home imbalance"), Triple("King","material mastery, stewardship, stability, enterprise","greed, stubborn control, status obsession")
            )
        )
        var id = 22
        for ((suit, meanings) in defs) {
            val element = when(suit) {"Wands"->"Fire";"Cups"->"Water";"Swords"->"Air";else->"Earth"}
            meanings.forEach { t ->
                val name = t.first
                val rank = when(name) {"Ace"->1;"Two"->2;"Three"->3;"Four"->4;"Five"->5;"Six"->6;"Seven"->7;"Eight"->8;"Nine"->9;"Ten"->10;"Page"->11;"Knight"->12;"Queen"->13;"King"->14;else->0}
                val court = if (rank >= 11) name else null
                val polarity = estimatePolarity(suit, rank, t.second)
                add(TarotCard(id++, "$name of $suit", "Minor", suit, element, rank, court, t.second, t.third, polarity))
            }
        }
    }

    private fun estimatePolarity(suit:String, rank:Int, text:String):Int {
        val negative = listOf("conflict","grief","hardship","heartbreak","anxiety","burden","restriction","ending","apathy","stalemate")
        if (negative.any { text.contains(it) }) return -1
        if (rank == 5 || rank == 10 && suit == "Swords") return -1
        return 1
    }

    private fun majorElement(name:String):String = when(name) {"The Fool"->"Air";"The Magician"->"Air";"The High Priestess"->"Water";"The Empress"->"Earth";"The Emperor"->"Fire";"The Hierophant"->"Earth";"The Lovers"->"Air";"The Chariot"->"Water";"Strength"->"Fire";"The Hermit"->"Earth";"Wheel of Fortune"->"Fire";"Justice"->"Air";"The Hanged Man"->"Water";"Death"->"Water";"Temperance"->"Fire";"The Devil"->"Earth";"The Tower"->"Fire";"The Star"->"Air";"The Moon"->"Water";"The Sun"->"Fire";"Judgement"->"Fire";else->"Earth"}

    fun nameFor(deck: TarotDeck, c: TarotCard): String {
        if (deck == TarotDeck.RWS || deck == TarotDeck.HAINDL && c.arcana == "Major") return c.name
        var n = c.name
        if (deck == TarotDeck.THOTH || deck == TarotDeck.TABULA) {
            n = when(c.name) {"The Magician"->"The Magus";"The High Priestess"->"The Priestess";"Strength"->"Lust";"Justice"->"Adjustment";"Temperance"->"Art";"Judgement"->"The Aeon";"The World"->"The Universe";else->c.name}
            if (c.arcana == "Minor") {
                n = when(c.court) {"King"->"Knight of ${c.suit}";"Knight"->"Prince of ${c.suit}";"Page"->"Princess of ${c.suit}";else->n}.replace("Pentacles","Disks")
            }
        } else if (deck == TarotDeck.HERMETIC) {
            n = when(c.name) {"The Fool"->"The Foolish Man";"The World"->"The Universe";else->c.name}
            if (c.court == "Page") n = "Princess of ${c.suit}"
        } else if (deck == TarotDeck.MARSEILLE) {
            n = when(c.name) {"The High Priestess"->"The Popess";"The Hierophant"->"The Pope";"The Tower"->"The House of God";else->c.name}
            if (c.arcana=="Minor") n = when(c.court) {"Page"->"Valet of ${c.suit}";"Knight"->"Cavalier of ${c.suit}";else->n}
        } else if (deck == TarotDeck.HAINDL && c.arcana=="Minor") {
            val suit = if(c.suit=="Pentacles") "Stones" else c.suit
            n = when(c.court) {"King"->"Father of $suit";"Queen"->"Mother of $suit";"Knight"->"Son of $suit";"Page"->"Daughter of $suit";else->"${c.name.substringBefore(" of ")} of $suit"}
        }
        return n
    }
}

object TarotInterpreter {
    fun draw(rng: EntropyEngine, deck: TarotDeck, preset: ReadingPreset, question: String, reversals: Boolean, customCards: List<String>, customPositions: List<String> = emptyList()): Pair<List<DrawnCard>, String> {
        val positions = if (preset.title == "Custom Spread" && customPositions.isNotEmpty()) customPositions.take(10) else preset.positions
        if (deck == TarotDeck.CUSTOM) {
            val uniqueCustom = customCards.distinct()
            require(uniqueCustom.isNotEmpty()) { "Add cards to the Tarot Archetypes custom bank first." }
            require(uniqueCustom.size >= positions.size) { "Custom Archetype Deck needs at least ${positions.size} different cards for this spread." }
            val available = uniqueCustom.indices.toMutableList()
            val out = positions.mapIndexed { i, pos ->
                val idx = rng.pick(available.size.toLong(), SystemClock.elapsedRealtimeNanos(), "custom|$question|$i|${uniqueCustom.joinToString("|")}").toInt()
                val chosen = available.removeAt(idx)
                val fake = TarotCard(chosen, uniqueCustom[chosen], "Custom", null, null, null, null, "the archetype, symbol or meaning you personally assign to ${uniqueCustom[chosen]}", "the blocked, internal, shadowed or inverted expression of ${uniqueCustom[chosen]}", 0)
                val rev = reversals && rng.pick(2, SystemClock.elapsedRealtimeNanos(), "rev|custom|$question|$i") == 1L
                DrawnCard(fake, uniqueCustom[chosen], rev, pos)
            }
            return out to synthesize(preset, question, out)
        }
        val available = TarotLibrary.cards.indices.toMutableList()
        val out = positions.mapIndexed { i, pos ->
            val idx = rng.pick(available.size.toLong(), SystemClock.elapsedRealtimeNanos(), "${deck.name}|$question|${preset.title}|$i").toInt()
            val card = TarotLibrary.cards[available.removeAt(idx)]
            val rev = reversals && rng.pick(2, SystemClock.elapsedRealtimeNanos(), "rev|${deck.name}|$question|$i|${card.id}") == 1L
            DrawnCard(card, TarotLibrary.nameFor(deck, card), rev, pos)
        }
        return out to synthesize(preset, question, out)
    }

    fun cardExplanation(deck: TarotDeck, d: DrawnCard): String {
        val meaning = if (d.reversed) d.card.reversed else d.card.upright
        val orientation = if (d.reversed) "Reversed" else "Upright"
        val lens = when(deck) {TarotDeck.THOTH->"Read through a Thoth/Thelemic force-and-transformation lens";TarotDeck.HERMETIC->"Read through a Golden Dawn, astrological and elemental lens";TarotDeck.TABULA->"Read through a Thoth-based decanic and archetypal-combination lens";TarotDeck.MARSEILLE->"Read through number, suit, sequence and archetypal pattern";TarotDeck.HAINDL->"Read through mythic, elemental and spiritual-development symbolism";TarotDeck.CUSTOM->"Read through your personal meaning for this archetype";else->"Read through the classic scenic-symbolic RWS lens"}
        return "$orientation • ${d.position}: $meaning. $lens."
    }

    fun synthesize(preset: ReadingPreset, question: String, cards: List<DrawnCard>): String {
        if (cards.isEmpty()) return ""
        val parts = mutableListOf<String>()
        parts += "Reading focus: ${question.trim().ifEmpty { "the matter you are asking about" }}."
        if (cards.size == 1) { val c=cards[0]; parts += "${c.displayName}${if(c.reversed) " reversed" else ""} is the center of the reading: ${if(c.reversed)c.card.reversed else c.card.upright}."; return parts.joinToString("\n\n") }
        for (i in 0 until cards.lastIndex) parts += relation(cards[i], cards[i+1], i+1)
        val majors = cards.count { it.card.arcana == "Major" }
        val reversals = cards.count { it.reversed }
        val courts = cards.count { it.card.court != null }
        val suitCounts = cards.mapNotNull { it.card.suit }.groupingBy { it }.eachCount()
        val dominant = suitCounts.maxByOrNull { it.value }
        val ranks = cards.mapNotNull { it.card.rank }.filter { it in 1..10 }
        val repeats = ranks.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        if (majors >= 2) parts += "$majors Major Arcana cards make the spread strongly archetypal: the question is being framed as a larger developmental turning point rather than only a small event."
        if (courts >= 2) parts += "$courts court cards increase the importance of personality, roles, social dynamics, or different modes of acting."
        if (dominant != null && dominant.value >= 2) parts += "${dominant.key} dominates the Minor Arcana (${dominant.value} cards), concentrating the reading in ${domain(dominant.key)}."
        if (reversals > cards.size / 2) parts += "Most cards are reversed, so the reading leans toward internalized, blocked, delayed, hidden, or reworked expressions rather than clean outward movement."
        if (repeats.isNotEmpty()) parts += "Repeated number pattern: ${repeats.joinToString()}. Repeated ranks echo the same developmental stage across different life domains."
        if (preset.title == "Yes / No") {
            val score = cards.sumOf { if(it.reversed) -it.card.polarity else it.card.polarity }
            val lean = when {score>=3->"strong YES lean";score>0->"YES lean";score<=-3->"strong NO / NOT YET lean";score<0->"NO / NOT YET lean";else->"mixed / conditional"}
            parts += "Directional result: $lean. Read the third card as the condition or advice that can change the outcome."
        }
        val first=cards.first(); val last=cards.last()
        parts += "Overall arc: the spread begins with ${first.displayName}${if(first.reversed) " reversed" else ""} in “${first.position}” and culminates in ${last.displayName}${if(last.reversed) " reversed" else ""} in “${last.position}”. Read the ending as the result of the earlier positions, not as an isolated card."
        return parts.joinToString("\n\n")
    }

    private fun relation(a: DrawnCard, b: DrawnCard, ordinal:Int):String {
        val transition = when {a.card.arcana=="Major"&&b.card.arcana=="Major"->"a major archetypal transition: ${a.displayName} sets the larger force, then ${b.displayName} develops or redirects it";a.card.arcana=="Major"&&b.card.arcana=="Minor"->"${a.displayName}'s larger archetype becomes concrete through ${b.displayName} in ${domain(b.card.suit)}";a.card.arcana=="Minor"&&b.card.arcana=="Major"->"the concrete situation of ${a.displayName} escalates into the larger lesson or turning point of ${b.displayName}";a.card.arcana=="Custom"||b.card.arcana=="Custom"->"${a.displayName} flows into ${b.displayName}; use your personal symbolic meanings, with the first position feeding the second";else->minorRelation(a,b)}
        val orient = when {a.reversed&&b.reversed->" Both are reversed, so the link is mostly internalized, blocked, delayed, or shadow-facing.";a.reversed->" The first card is reversed, so the transition begins from a blocked or internal condition.";b.reversed->" The second card is reversed, so the incoming result is complicated, delayed, internalized, or shadowed.";else->""}
        return "Link $ordinal, “${a.position}” → “${b.position}”: $transition.$orient"
    }
    private fun minorRelation(a:DrawnCard,b:DrawnCard):String { val e=elemental(a.card.element,b.card.element); val suitText=if(a.card.suit==b.card.suit&&a.card.suit!=null)" Same suit strengthens the theme of ${domain(a.card.suit)}." else ""; return "${a.displayName} moves into ${b.displayName}. $e$suitText${rankRelation(a.card.rank,b.card.rank)}".trim() }
    private fun elemental(a:String?,b:String?):String { if(a==null||b==null)return "Their relationship is read mainly by position and archetype."; if(a==b)return "Matching $a elements strengthen and intensify one another."; val set=setOf(a,b); return when {set==setOf("Fire","Air")->"Fire and Air support one another, joining drive with thought, communication or strategy.";set==setOf("Water","Earth")->"Water and Earth support one another, giving feeling a container and giving material reality emotional meaning.";set==setOf("Fire","Water")->"Fire and Water weaken or contest one another, creating tension between drive and feeling.";set==setOf("Air","Earth")->"Air and Earth weaken or contest one another, creating tension between ideas and practical reality.";else->"The elements are relatively neutral, so position and card meaning carry more weight."} }
    private fun rankRelation(a:Int?,b:Int?):String { if(a==null||b==null||a !in 1..10||b !in 1..10)return ""; return when {a==b->" The repeated rank $a echoes the same developmental stage across two domains.";b==a+1->" The number rises from $a to $b, suggesting sequential development or escalation.";b==a-1->" The number falls from $a to $b, suggesting return, simplification, review or loss of momentum.";else->""} }
    private fun domain(suit:String?):String = when(suit) {"Wands"->"action, ambition, creativity, sexuality and will";"Cups"->"emotion, love, intuition and relationship";"Swords"->"thought, conflict, truth, communication and strategy";"Pentacles"->"money, body, work, resources and material reality";else->"the concrete situation"}
}
