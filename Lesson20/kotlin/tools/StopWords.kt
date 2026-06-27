package tools

val englishStopWords = setOf(
    "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
    "of", "with", "by", "from", "as", "is", "was", "are", "were", "be",
    "been", "being", "have", "has", "had", "do", "does", "did", "will",
    "would", "could", "should", "may", "might", "shall", "can", "need",
    "this", "that", "these", "those", "it", "its", "they", "them", "their",
    "he", "she", "him", "her", "his", "my", "your", "our", "we", "you",
    "i", "me", "not", "no", "nor", "so", "if", "then", "than", "too",
    "very", "just", "about", "also", "into", "over", "after", "before",
    "between", "through", "during", "because", "while", "when", "where",
    "why", "how", "all", "each", "every", "both", "few", "more", "most",
    "other", "some", "such", "only", "own", "same", "here", "there",
    "up", "down", "out", "off", "above", "below", "again", "further",
    "once", "then", "now", "what", "which", "who", "whom"
)

val russianStopWords = setOf(
    "и", "в", "во", "не", "на", "я", "он", "она", "оно", "они",
    "мы", "вы", "ты", "меня", "мне", "его", "её", "ее", "их", "ним",
    "нас", "вас", "вам", "нам", "себя", "это", "этот", "эта", "эти",
    "тот", "та", "те", "все", "всё", "весь", "вся", "всех", "всем",
    "что", "чтобы", "как", "так", "когда", "где", "тут", "там",
    "здесь", "кто", "какой", "какая", "какие", "какое", "каждое",
    "каждый", "каждая", "всегда", "никогда", "ничего", "ничто",
    "потому", "поэтому", "зачем", "почему", "для", "про", "без",
    "над", "под", "об", "обо", "от", "ото", "из", "изо", "у", "при",
    "через", "перед", "после", "между", "с", "со", "к", "ко",
    "а", "но", "да", "же", "бы", "ли", "либо", "нибудь", "уже",
    "ещё", "еще", "уже", "только", "даже", "ведь", "вот", "вон",
    "ну", "впрочем", "однако", "значит", "будто", "словно", "точно",
    "разве", "неужели", "едва", "вряд", "вроде", "более", "менее",
    "самый", "самая", "самое", "сами", "самого", "самых", "самой",
    "больше", "меньше", "лучше", "хуже", "просто", "прямо",
    "ладно", "хорошо", "плохо", "надо", "нужно", "можно", "нельзя",
    "наверное", "конечно", "возможно", "действительно",
    "кстати", "вообще", "например", "между", "тем", "причем",
    "причём", "итак", "также", "то", "если", "пока", "пусть"
)

fun detectStopWords(text: String): Set<String> {
    return if (text.codePoints().anyMatch { cp -> Character.UnicodeBlock.of(cp) == Character.UnicodeBlock.CYRILLIC }) {
        russianStopWords
    } else {
        englishStopWords
    }
}
