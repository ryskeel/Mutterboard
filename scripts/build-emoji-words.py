#!/usr/bin/env python3
"""Build the table behind the emoji in the keyboard's suggestion bar.

    keyboard/app/src/main/assets/common/dictionaries/en_emoji.tsv

One line per word: the word, then up to three emoji, best first. The keyboard
shows the first one the phone can draw.

The keywords the emoji picker searches are the starting point, but two things
about them are wrong for a bar that reacts to every word typed. Search can show
forty results, so its order does not matter; the bar has one slot, and "party"
would lead with a tongue-out face (keyword "party", and smileys sort first). And
the keywords are written for someone looking for an emoji, so read against
ordinary typing they misfire: "one" is the one-piece swimsuit, "home" is home
improvement's wrench, "you" is a kiss.

So the words people type constantly get an emoji only if PICKED says so, chosen
by hand, and the keywords only fill in rarer words, where the word is usually a
thing and is seen too rarely for a stretch to grate. POPULAR, roughly Unicode's
emoji frequency list, settles ties between emoji that share a word. Keep
COMMON_WORDS and PICKED honest with --review after changing anything.

Usage:
    scripts/build-emoji-words.py          writes the table
    scripts/build-emoji-words.py --review prints common words and their emoji
"""

import json
import re
import sys
from pathlib import Path

ASSETS = Path(__file__).resolve().parent.parent / "keyboard/app/src/main/assets/common"
KEYWORDS = ASSETS / "emoji_search/en.tsv"
DICTIONARY = ASSETS / "dictionaries/en_base.json"
OUTPUT = ASSETS / "dictionaries/en_emoji.tsv"

MAX_PER_WORD = 3
# Below this rank in the dictionary a word needs PICKED to get an emoji.
COMMON_WORDS = 7000
# Two-letter keywords are mostly abbreviations of something else ("de" is Cinco de Mayo).
WORD = re.compile(r"[a-z]{3,}$")

POPULAR = """
😂 ❤️ 🤣 👍 😭 🙏 😘 🥰 😍 😊 🎉 😁 💕 🥺 😅 🔥 ☺️ 🤦 ♥️ 🤷 🙄 😆 🤗 😉 🎂 🤔 👏 🙂 😳 🥳
😎 👌 💜 😔 💪 ✨ 💖 👀 😋 😏 😢 👉 💗 😩 💯 🌹 💞 🎈 💙 😃 😡 💐 😜 🙈 🤞 😄 🤤 🙌 🤪 ❣️
😀 💋 💀 👇 💔 😌 💓 🤩 🙃 😬 😱 😴 🤭 😐 🌞 😒 😇 🌸 😈 🎶 ✌️ 🎊 🥵 😞 💚 ☀️ 🖤 💰 😚 👑
🎁 💥 🙋 ☹️ 😑 🥴 👈 💩 ✅ 👋 🤧 😕
""".split()

# Never from the keywords. An emoji answering to who someone is reads badly, and
# the rest are words the keywords only reach through a phrase ("mayo", "pile").
SKIP = set("""
arab asian bisexual buddhism buddhist christian gay genderqueer glbtq hindu
homosexual islam jew jewish judaism khalsa lesbian lgbt lgbtq lgbtqia mexican
muslim orthodox queer sikh trans transgender
absolutely blame blowing chin chuck emoji guess hail heartbreaker horrible mao
mayo pile rewindershins shade shy silly slash ugly whistle
""".split())

PICKED_LINES = """
smile smiles smiled smiling smiley = 😊 😁 🙂
happy glad joy cheerful = 😊 😁 😄
sad unhappy depressed = 😢 😔 😞
cry cries crying cried tears = 😭 😢
laugh laughs laughing laughed funny hilarious = 😂 🤣 😆
lol haha hahaha lmao rofl = 😂 🤣 😆
joke joking kidding = 😜 😂
love loved lovely adore = ❤️ 😍 🥰
heart hearts = ❤️ 💕 💖
kiss kisses kissing = 😘 💋
hug hugs hugging = 🤗 🫂
wink winking = 😉
excited excitement = 🤩 🎉
oops = 🙈 😅
huh = 🤔
lonely = 😔
weird = 🤨
angry mad furious = 😡 😠 🤬
annoyed = 😒 🙄
scared afraid terrified fear = 😱 😨
shocked surprised surprise = 😮 😱
wow omg = 😮 😱 🤯
tired exhausted sleepy = 😴 🥱
sleep sleeping nap = 😴 💤
bored boring = 😑 🥱
confused = 😕 🤔
thinking hmm = 🤔
worried nervous anxious = 😟 😬
embarrassed = 😳
cool awesome = 😎 🔥
amazing incredible fantastic = 🤩 🔥
great = 👏 👍
good = 👍 😊
nice = 👌 😊
perfect = 👌 💯
yes yep yeah = 👍 ✅
ok okay = 👌 👍
thanks thank thankful grateful = 🙏 😊
please = 🙏 🥺
pray praying prayer = 🙏
sorry = 😔 🙏
sick ill = 🤒 🤢
hot = 🥵 🔥
cold freezing = 🥶 ❄️
hungry = 😋 🤤
yum yummy delicious tasty = 😋 🤤
pretty beautiful gorgeous = 😍 ✨
cute adorable = 🥰 🥺
crazy = 🤪
dead = 💀
hello hi hey bye goodbye = 👋
peace = ✌️ 🕊️
strong muscle gym workout = 💪
party = 🎉 🥳 🎊
celebrate celebrating celebration congrats congratulations = 🎉 🥳 🍾
birthday = 🎂 🎉 🥳
cake = 🎂 🍰
gift present = 🎁
christmas = 🎄 🎅 🎁
halloween = 🎃 👻
wedding married = 💍 💒
win won winner champion = 🏆 🥇
fire = 🔥
star stars = ⭐ ✨
sun sunny = ☀️ 😎
moon = 🌙
rain raining rainy = 🌧️ ☔
snow snowing = ❄️ ⛄
storm = ⛈️
water = 💧 🌊
ocean sea = 🌊
beach = 🏖️ 🌴
mountain mountains = ⛰️ 🏔️
flower flowers = 🌸 🌹 💐
rose = 🌹
tree trees = 🌳 🌲
world earth = 🌍
home house = 🏠
school = 🏫 🎒
hospital = 🏥
church = ⛪
work job = 💼
office = 🏢
meeting = 📅
clock = ⏰ 🕐
money cash = 💰 💵
music song = 🎵 🎶
movie film = 🎬 🍿
game games = 🎮
book books reading = 📖 📚
phone = 📱
computer = 💻
camera photo picture = 📷 📸
car = 🚗
bus = 🚌
train = 🚆
plane flight flying = ✈️
travel vacation = ✈️ 🧳
boat ship = 🚢 ⛵
bike = 🚲
run running = 🏃
walk walking = 🚶
dance dancing = 💃 🕺
sing singing = 🎤 🎶
football = 🏈
soccer = ⚽
basketball = 🏀
baseball = ⚾
tennis = 🎾
golf = ⛳
fish fishing = 🐟 🎣
dog dogs = 🐶 🐕
cat cats = 🐱 🐈
horse = 🐴
bird = 🐦
bear = 🐻
chicken = 🐔 🍗
baby = 👶
mom mum = 👩
dad = 👨
boy = 👦
girl = 👧
man = 👨
woman = 👩
family = 👪
friend friends = 👯 🧑‍🤝‍🧑
king = 👑
queen = 👑
food eating = 🍽️ 😋
dinner lunch = 🍽️
breakfast = 🍳 🥞
coffee = ☕
tea = 🍵
beer = 🍺 🍻
wine = 🍷
drink drinks drinking = 🍹 🍺
ghost = 👻
rocket = 🚀
idea = 💡
key = 🔑
gun = 🔫
bomb = 💣
email mail = 📧
question = ❓
finished = ✅
check = ✅
stop = 🛑
fast quick = 💨
"""

PICKED = {
    word: emoji.split()
    for line in PICKED_LINES.strip().splitlines()
    for words, emoji in [line.split(" = ")]
    for word in words.split()
}


def load_keywords():
    """word -> {emoji: (how closely its name matches, how many words it answers to)}

    Keywords only: name words would put an emoji on "the" and "of".
    """
    matches = {}
    for line in KEYWORDS.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        emoji, name, keywords = parts[0], parts[1].lower(), parts[2].lower().split("|")
        name_words = name.split()
        for word in keywords + [name]:
            if not WORD.match(word):
                continue
            closeness = 0 if word == name else 1 if word in name_words else 2
            matches.setdefault(word, {})[emoji] = (closeness, len(keywords))
    return matches


def rank(candidates):
    """An emoji named for the word first, then the popular one, then the most specific."""
    popular = {emoji: i for i, emoji in enumerate(POPULAR)}
    def key(emoji):
        closeness, breadth = candidates[emoji]
        return (closeness, popular.get(emoji, len(POPULAR)), breadth, emoji)
    return sorted(candidates, key=key)


def dictionary_words():
    entries = json.loads(DICTIONARY.read_text(encoding="utf-8"))
    return [entry["w"].lower() for entry in sorted(entries, key=lambda e: -e["f"])]


def build():
    common = set(dictionary_words()[:COMMON_WORDS])
    table = {
        word: rank(candidates)[:MAX_PER_WORD]
        for word, candidates in load_keywords().items()
        if word not in common and word not in SKIP
    }
    table.update(PICKED)
    return dict(sorted(table.items()))


def review(table):
    words = dictionary_words()
    known = list(dict.fromkeys(word for word in words if word in table))
    for word in known:
        print(word, table[word][0])
    print("not in the dictionary:", *[w + table[w][0] for w in table if w not in set(words)])
    print(f"{len(table)} words, {len(known)} in the dictionary, {len(PICKED)} picked")


def main():
    table = build()
    if "--review" in sys.argv:
        review(table)
        return
    with OUTPUT.open("w", encoding="utf-8") as out:
        for word, emoji in table.items():
            out.write(word + "\t" + " ".join(emoji) + "\n")
    print(f"{len(table)} words, {OUTPUT.stat().st_size} bytes")


if __name__ == "__main__":
    main()
