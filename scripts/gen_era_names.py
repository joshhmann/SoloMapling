"""Era-style (2004-2010 GMS) bot names: legal [A-Za-z0-9]{3,12}, unique ignoring case, none from a given exclude file."""
import random, re, sys

rng = random.Random(2006)
LEGAL = re.compile(r"^[A-Za-z0-9]{3,12}$")

# Words players of that era built names from: game things, anime/emo/scene words, gamer slang, cute stuff.
GAME = ["Maple", "Mushy", "Shroom", "Snail", "Slime", "Pig", "Ribbon", "Balrog", "Zakum", "Pianus", "Papu", "Horntail",
        "Ludi", "Orbis", "Kerning", "Henny", "Ellinia", "Perion", "Leafre", "Elnath", "Aqua", "Toy", "Clock", "Yeti",
        "Pepe", "Lupin", "Zombie", "Wraith", "Drake", "Golem", "Stirge", "Octo", "Curse", "Ghost", "Lorang",
        "Clang", "Tauro", "Lycan", "Crimson", "Ninja", "Claw", "Star", "Arrow", "Bow", "Wand", "Staff", "Sword",
        "Spear", "Axe", "Dagger", "Knuckle", "Gun", "Scroll", "Meso", "Cash", "Pet", "Chair", "Hat",
        "Cape", "Zhelm", "Pink", "Bean", "Hero", "Bishop", "Priest", "Cleric", "Hermit", "Shadow", "Sader", "Dk",
        "Bucc", "Sair", "Ranger", "Sniper", "Mage", "Wiz", "Bandit", "Page", "Fighter", "Hunter",
        "Xbow", "Pally", "Corsair", "Aran", "Evan"]
SCENE = ["Dark", "Shadow", "Blood", "Death", "Fire", "Ice", "Storm", "Thunder", "Night", "Moon", "Sun", "Star",
         "Dragon", "Demon", "Angel", "Devil", "Soul", "Spirit", "Blade", "Silent", "Lost", "Broken", "Fallen", "Eternal",
         "Mystic", "Crystal", "Silver", "Gold", "Black", "White", "Red", "Blue", "Neo", "Cyber", "Hyper", "Ultra",
         "Mega", "Super", "Epic", "Kawaii", "Chibi", "Neko", "Kitty", "Bunny", "Panda", "Cookie", "Candy", "Sugar",
         "Cupcake", "Muffin", "Bubble", "Sweet", "Cutie", "Lil", "Baby", "Princess", "Prince", "King", "Queen",
         "Lord", "Master", "Pro", "Noob", "Pwn", "Leet", "Haxx", "Ownage", "Skater", "Emo", "Punk", "Rocker",
         "Gangsta", "Thug", "Tiger", "Wolf", "Fox", "Hawk", "Raven", "Phoenix", "Viper", "Cobra", "Sniper", "Hunter",
         "Killer", "Slayer", "Reaper", "Rogue", "Samurai", "Sasuke", "Naruto", "Kakashi", "Goku", "Vegeta", "Ichigo",
         "Inuyasha", "Kirby", "Pika", "Sonic", "Link", "Zelda", "Cloud", "Sephiroth", "Riku", "Sora", "Kairi",
         "Yuna", "Tidus", "Ash", "Misty", "Panda", "Monkey", "Pickle", "Taco", "Nacho", "Waffle", "Pancake", "Noodle",
         "Rice", "Sushi", "Boba", "Mochi", "Kimchi", "Ramen", "Dumpling", "Pudding", "Jelly", "Toast"]
SUFFIX_WORDS = ["z", "x", "xx", "zz", "kun", "chan", "sama", "ie", "y", "o", "er", "ster", "boy", "girl", "boi", "gurl",
                "kid", "man", "dude", "ninja", "pwnz", "ownz", "r", "rz", "4life", "4ever"]
YEARS = ["04", "05", "06", "07", "08", "09", "10", "92", "93", "94", "95", "96", "97", "98"]

def leet(w):
    table = {"a": "4", "e": "3", "i": "1", "o": "0", "s": "5"}
    out = "".join(table[c] if c in table and rng.random() < 0.5 else c for c in w.lower())
    return out.capitalize() if rng.random() < 0.6 else out

def swap_case_style(w):
    return w.lower() if rng.random() < 0.35 else w

def make():
    a, b = rng.choice(SCENE + GAME), rng.choice(GAME + SCENE)
    style = rng.random()
    if style < 0.16:
        core = a + b
        name = "xX" + core + "Xx" if len(core) <= 8 else core
    elif style < 0.30:
        name = a + b
    elif style < 0.42:
        name = a + str(rng.choice([rng.randint(1, 9), rng.randint(10, 99), rng.randint(10, 99), rng.randint(100, 999)]))
    elif style < 0.52:
        name = a + b + rng.choice(YEARS)
    elif style < 0.60:
        name = "ii" + a + rng.choice(["z", "x", "", "s"])
    elif style < 0.70:
        name = a + rng.choice(SUFFIX_WORDS)
    elif style < 0.74:
        name = leet(a) + b
    elif style < 0.83:
        name = rng.choice(["Lil", "Its", "Im", "Ur", "Mr", "Ms", "Sir", "Da", "The", "Iam"]) + a
    elif style < 0.90:
        name = a + rng.choice(["Pwnz", "Ownz", "Rulez", "Fan", "Luv", "Lover", "Ftw", "Lol"])
    elif style < 0.95:
        v = a.lower()
        name = v + v[-1] * rng.randint(1, 2) + rng.choice(["", "y", str(rng.randint(1, 9))])
    else:
        name = a[:6] + b[:6]
    return swap_case_style(name)

exclude = set()
for path in sys.argv[2:]:
    for line in open(path, encoding="utf-8", errors="ignore"):
        exclude.add(line.strip().lower())

want = int(sys.argv[1])
names, seen = [], set()
tries = 0
while len(names) < want and tries < want * 50:
    tries += 1
    n = make()
    k = n.lower()
    if LEGAL.match(n) and k not in seen and k not in exclude:
        seen.add(k)
        names.append(n)
print("# Era-style (2004-2010 GMS) bot names, generated - not real players' handles.")
print("# One per line; BotNames reads every *.txt here (see README.md).")
print("\n".join(names))
