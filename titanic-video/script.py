"""Narration script for "Titanic: The Unsinkable Ship".

Each scene is a list of sentences. Every sentence is synthesised separately so
that we know its exact duration; that drives both the subtitle timing and the
length of the scene's animation.
"""

VOICE = "en-GB-RyanNeural"
RATE = "-6%"
PITCH = "-2Hz"

# Seconds of breathing room before the first line / after the last line of a scene.
LEAD_IN = 1.2
TAIL = 1.6
GAP = 0.45  # pause between sentences

# Extra silence after specific sentences, so big visual moments get room to breathe.
PAUSES = {
    ("intro", 3): 1.0,
    ("iceberg", 1): 0.6,
    ("iceberg", 2): 1.2,
    ("iceberg", 3): 0.8,
    ("sinking", 1): 1.0,
    ("sinking", 2): 4.0,
    ("sinking", 3): 1.5,
    ("legacy", 3): 4.5,
}

SCENES = [
    ("intro", [
        "April, nineteen twelve.",
        "The largest moving object ever built by human hands slips out of Southampton, bound for New York.",
        "Her name is Titanic.",
        "And the world believes she cannot sink.",
    ]),
    ("build", [
        "She was born in Belfast, at the Harland and Wolff shipyard, beneath a steel gantry so vast it had to be built just for her.",
        "Fifteen thousand workers spent nearly three years riveting her together.",
        "Two hundred and sixty nine metres long, and over forty six thousand tons.",
        "Three million rivets held her hull plates in place.",
    ]),
    ("engines", [
        "So how did a floating city actually move?",
        "Deep in her belly, twenty nine boilers were fed by one hundred and fifty nine coal furnaces, shovelled by hand around the clock.",
        "Their steam drove two giant four-cylinder engines, each turning an outer propeller.",
        "Then the used steam was sent on to a low pressure turbine, spinning the centre propeller for free.",
        "Together they produced around fifty thousand horsepower, pushing her to twenty three knots.",
    ]),
    ("compartments", [
        "But her true pride lay below the waterline.",
        "Fifteen steel bulkheads divided the hull into sixteen watertight compartments.",
        "With a single switch on the bridge, electric doors could slam shut, sealing each one off.",
        "She could stay afloat with any two compartments flooded, or even the first four.",
        "Shipbuilder magazine called her practically unsinkable, and the press quietly dropped the word practically.",
    ]),
    ("flaw", [
        "Yet the design hid a fatal secret.",
        "The bulkheads were not sealed at the top. They rose only a few decks above the waterline.",
        "Like an ice cube tray tipped forward, once one compartment filled, water would spill over into the next.",
        "And with so much confidence in the ship, she carried just twenty lifeboats. Enough for barely half the people on board.",
    ]),
    ("iceberg", [
        "On the night of April the fourteenth, the sea was flat calm, and there was no moon.",
        "At eleven forty pm, lookout Frederick Fleet saw a dark shape dead ahead.",
        "Iceberg, right ahead!",
        "The ship turned, but too late. The ice scraped along her starboard side for nearly ninety metres.",
        "Not one giant gash, but a line of buckled plates and popped rivets, opening six compartments to the sea.",
    ]),
    ("sinking", [
        "Six compartments. Two more than she could survive.",
        "Her bow dipped lower, and one by one the bulkheads were overtopped.",
        "At two twenty in the morning, her stern rose high into the night sky. Then she broke apart, and was gone.",
        "More than fifteen hundred people lost their lives.",
    ]),
    ("legacy", [
        "Her loss changed the sea forever.",
        "New laws demanded lifeboats for everyone, radio watch around the clock, and an international ice patrol.",
        "In nineteen eighty five, her wreck was found, nearly four kilometres beneath the Atlantic.",
        "Titanic was never truly unsinkable. But her story never sank.",
    ]),
]
