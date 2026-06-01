package com.rewayaat.tafsir;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps surah names to numbers, handling transliteration variants.
 * Supports 114 surahs with common English and Arabic transliterations.
 * Also supports native Arabic surah names.
 */
public class SurahNameResolver {

    private static final Map<String, Integer> SURAH_MAP = new HashMap<>();

    static {
        // Surah 1 - Al-Fatiha
        putVariants(1, "Al-Fatiha", "Fatiha", "al-Fatiha", "al-Fatihah", "Fatihah",
                    "The Opening", "Al-Hamd", "Hamd", "Surah al-Hamd",
                    "الفاتحة", "الفاتحه", "سورة الفاتحة", "الحمد", "سورة الحمد", "حمد", "ام الكتاب");

        // Surah 2 - Al-Baqarah
        putVariants(2, "Al-Baqarah", "Baqarah", "Baqara", "Baqarah", "al-Baqarah",
                    "The Cow", "Surah Baqarah", "Surah Baqara", "Baqra",
                    "البقرة", "سورة البقرة");

        // Surah 3 - Ali 'Imran
        putVariants(3, "Ali 'Imran", "Al-Imran", "Imran", "Ali Imran", "Aal-e-Imran",
                    "The Family of Imran", "Family of Imran",
                    "آل عمران", "سورة آل عمران", "عمران");

        // Surah 4 - An-Nisa
        putVariants(4, "An-Nisa", "Nisa", "an-Nisa", "An-Nisaa", "Nisaa",
                    "Women", "The Women", "Surah an-Nisa",
                    "النساء", "سورة النساء");

        // Surah 5 - Al-Ma'idah
        putVariants(5, "Al-Ma'idah", "Ma'idah", "Maidah", "Maida", "al-Ma'idah",
                    "The Table Spread", "The Table",
                    "المائدة", "سورة المائدة");

        // Surah 6 - Al-An'am
        putVariants(6, "Al-An'am", "An'am", "Anam", "al-An'am",
                    "The Cattle", "The Livestock",
                    "الأنعام", "سورة الأنعام");

        // Surah 7 - Al-A'raf
        putVariants(7, "Al-A'raf", "A'raf", "Araf", "al-A'raf", "Al-A'raaf",
                    "The Elevated Places", "The Heights",
                    "الأعراف", "سورة الأعراف");

        // Surah 8 - Al-Anfal
        putVariants(8, "Al-Anfal", "Anfal", "al-Anfal",
                    "The Spoils of War", "The Booty",
                    "الأنفال", "سورة الأنفال");

        // Surah 9 - At-Tawbah
        putVariants(9, "At-Tawbah", "Tawbah", "Bara'ah", "at-Tawbah", "Tauba",
                    "The Repentance", "Repentance",
                    "التوبة", "سورة التوبة", "براءة");

        // Surah 10 - Yunus
        putVariants(10, "Yunus", "Jonah", "Jonah (Yunus)",
                    "يونس", "سورة يونس");

        // Surah 11 - Hud
        putVariants(11, "Hud", "Hud",
                    "هود", "سورة هود");

        // Surah 12 - Yusuf
        putVariants(12, "Yusuf", "Yusuf", "Joseph", "Surah Yusuf",
                    "يوسف", "سورة يوسف");

        // Surah 13 - Ar-Ra'd
        putVariants(13, "Ar-Ra'd", "Ra'd", "Rad", "ar-Ra'd", "Ar-Raad",
                    "The Thunder",
                    "الرعد", "سورة الرعد");

        // Surah 14 - Ibrahim
        putVariants(14, "Ibrahim", "Abraham", "Surah Ibrahim",
                    "إبراهيم", "سورة إبراهيم");

        // Surah 15 - Al-Hijr
        putVariants(15, "Al-Hijr", "Hijr", "al-Hijr",
                    "The Rocky Tract", "The Stoneland",
                    "الحجر", "سورة الحجر");

        // Surah 16 - An-Nahl
        putVariants(16, "An-Nahl", "Nahl", "an-Nahl",
                    "The Bees", "The Bee",
                    "النحل", "سورة النحل");

        // Surah 17 - Al-Isra
        putVariants(17, "Al-Isra", "Isra", "Bani Isra'il", "al-Isra",
                    "The Night Journey", "The Children of Israel",
                    "الإسراء", "سورة الإسراء", "بنى اسرائيل", "بني إسرائيل");

        // Surah 18 - Al-Kahf
        putVariants(18, "Al-Kahf", "Kahf", "al-Kahf", "Surah al-Kahf",
                    "The Cave", "The Cave (Al-Kahf)",
                    "الكهف", "سورة الكهف");

        // Surah 19 - Maryam
        putVariants(19, "Maryam", "Maryam", "Mary", "Surah Maryam",
                    "Mary",
                    "مريم", "سورة مريم");

        // Surah 20 - Ta-Ha
        putVariants(20, "Ta-Ha", "Taha", "Ta-Ha",
                    "طه", "سورة طه");

        // Surah 21 - Al-Anbiya
        putVariants(21, "Al-Anbiya", "Anbiya", "Anbiya'", "al-Anbiya",
                    "The Prophets", "The Prophets (Al-Anbiya)",
                    "الأنبياء", "سورة الأنبياء");

        // Surah 22 - Al-Hajj
        putVariants(22, "Al-Hajj", "Hajj", "Haj", "al-Hajj",
                    "The Pilgrimage", "The Pilgrimage (Al-Hajj)",
                    "الحج", "سورة الحج");

        // Surah 23 - Al-Mu'minun
        putVariants(23, "Al-Mu'minun", "Mu'minun", "Muminun", "al-Mu'minun",
                    "The Believers", "The Believers (Al-Mu'minun)",
                    "المؤمنون", "سورة المؤمنون");

        // Surah 24 - An-Nur
        putVariants(24, "An-Nur", "Nur", "an-Nur", "Al-Noor",
                    "The Light", "Light",
                    "النور", "سورة النور");

        // Surah 25 - Al-Furqan
        putVariants(25, "Al-Furqan", "Furqan", "al-Furqan",
                    "The Criterion", "The Criterion (Al-Furqan)",
                    "الفرقان", "سورة الفرقان");

        // Surah 26 - Ash-Shu'ara
        putVariants(26, "Ash-Shu'ara", "Shu'ara", "Shuara", "ash-Shu'ara",
                    "The Poets", "The Poets (Ash-Shu'ara)",
                    "الشعراء", "سورة الشعراء");

        // Surah 27 - An-Naml
        putVariants(27, "An-Naml", "Naml", "an-Naml",
                    "The Ant", "The Ant (An-Naml)",
                    "النمل", "سورة النمل");

        // Surah 28 - Al-Qasas
        putVariants(28, "Al-Qasas", "Qasas", "al-Qasas",
                    "The Stories", "The Stories (Al-Qasas)",
                    "القصص", "سورة القصص");

        // Surah 29 - Al-Ankabut
        putVariants(29, "Al-Ankabut", "Ankabut", "al-Ankabut",
                    "The Spider", "The Spider (Al-Ankabut)",
                    "العنكبوت", "سورة العنكبوت");

        // Surah 30 - Ar-Rum
        putVariants(30, "Ar-Rum", "Ar-Room", "Rum", "Room", "ar-Rum", "ar-Room",
                    "The Romans", "The Romans (Ar-Rum)",
                    "الروم", "سورة الروم");

        // Surah 31 - Luqman
        putVariants(31, "Luqman", "Luqman", "Surah Luqman",
                    "لقمان", "سورة لقمان");

        // Surah 32 - As-Sajdah
        putVariants(32, "As-Sajdah", "Sajdah", "Sajda", "as-Sajdah",
                    "The Prostration", "The Prostration (As-Sajdah)",
                    "السجدة", "سورة السجدة");

        // Surah 33 - Al-Ahzab
        putVariants(33, "Al-Ahzab", "Ahzab", "al-Ahzab",
                    "The Combined Forces", "The Clans", "The Confederates",
                    "الأحزاب", "سورة الأحزاب");

        // Surah 34 - Saba
        putVariants(34, "Saba", "As-Saba", "Saba'", "Sheba",
                    "Sheba", "The Saba",
                    "سبأ", "سورة سبأ");

        // Surah 35 - Fatir
        putVariants(35, "Fatir", "Al-Fatir", "Fatir", "The Originator", "The Angels",
                    "فاطر", "سورة فاطر", "الملاكة");

        // Surah 36 - Ya-Sin
        putVariants(36, "Ya-Sin", "Yasin", "Ya-Sin", "Yaseen", "Yasin",
                    "يس", "سورة يس");

        // Surah 37 - As-Saffat
        putVariants(37, "As-Saffat", "Saffat", "as-Saffat",
                    "Those who set the Ranks", "The Rangers",
                    "الصافات", "سورة الصافات");

        // Surah 38 - Sad
        putVariants(38, "Sad", "Sad", "Saad", "The Letter Saad",
                    "ص", "سورة ص");

        // Surah 39 - Az-Zumar
        putVariants(39, "Az-Zumar", "Zumar", "az-Zumar",
                    "The Troops", "The Groups", "The Crowds",
                    "الزمر", "سورة الزمر");

        // Surah 40 - Ghafir
        putVariants(40, "Ghafir", "Al-Mu'min", "Mu'min", "Ghafir", "The Forgiver",
                    "غافر", "سورة غافر", "المؤمن");

        // Surah 41 - Fussilat
        putVariants(41, "Fussilat", "Fussilat", "Ha Mim", "Explained in Detail",
                    "فصلت", "سورة فصلت", "حم");

        // Surah 42 - Ash-Shura
        putVariants(42, "Ash-Shura", "Shura", "ash-Shura",
                    "The Consultation", "The Consultation (Ash-Shura)",
                    "الشورى", "سورة الشورى");

        // Surah 43 - Az-Zukhruf
        putVariants(43, "Az-Zukhruf", "Zukhruf", "az-Zukhruf",
                    "The Ornaments of Gold", "The Ornaments",
                    "الزخرف", "سورة الزخرف");

        // Surah 44 - Ad-Dukhan
        putVariants(44, "Ad-Dukhan", "Dukhan", "ad-Dukhan",
                    "The Smoke", "The Smoke (Ad-Dukhan)",
                    "الدخان", "سورة الدخان");

        // Surah 45 - Al-Jathiyah
        putVariants(45, "Al-Jathiyah", "Jathiyah", "al-Jathiyah",
                    "The Crouching", "The Kneeling",
                    "الجاثية", "سورة الجاثية");

        // Surah 46 - Al-Ahqaf
        putVariants(46, "Al-Ahqaf", "Ahqaf", "al-Ahqaf",
                    "The Wind-Curved Sandhills", "The Sand Dunes",
                    "الأحقاف", "سورة الأحقاف");

        // Surah 47 - Muhammad
        putVariants(47, "Muhammad", "Muhammad", "Surah Muhammad",
                    "محمد", "سورة محمد");

        // Surah 48 - Al-Fath
        putVariants(48, "Al-Fath", "Fath", "al-Fath",
                    "The Victory", "The Victory (Al-Fath)",
                    "الفتح", "سورة الفتح");

        // Surah 49 - Al-Hujurat
        putVariants(49, "Al-Hujurat", "Hujurat", "al-Hujurat",
                    "The Rooms", "The Chambers", "The Apartments",
                    "الحجرات", "سورة الحجرات");

        // Surah 50 - Qaf
        putVariants(50, "Qaf", "Qaf", "The Letter Qaf",
                    "ق", "سورة ق");

        // Surah 51 - Adh-Dhariyat
        putVariants(51, "Adh-Dhariyat", "Dhariyat", "adh-Dhariyat",
                    "The Winnowing Winds", "The Scatterers",
                    "الذاريات", "سورة الذاريات");

        // Surah 52 - At-Tur
        putVariants(52, "At-Tur", "Tur", "at-Tur",
                    "The Mount", "The Mountain",
                    "الطور", "سورة الطور");

        // Surah 53 - An-Najm
        putVariants(53, "An-Najm", "Najm", "an-Najm",
                    "The Star", "The Star (An-Najm)",
                    "النجم", "سورة النجم");

        // Surah 54 - Al-Qamar
        putVariants(54, "Al-Qamar", "Qamar", "al-Qamar",
                    "The Moon", "The Moon (Al-Qamar)",
                    "القمر", "سورة القمر");

        // Surah 55 - Ar-Rahman
        putVariants(55, "Ar-Rahman", "Rahman", "ar-Rahman",
                    "The Beneficent", "The Merciful",
                    "الرحمن", "سورة الرحمن");

        // Surah 56 - Al-Waqi'ah
        putVariants(56, "Al-Waqi'ah", "Waqi'ah", "Waqiah", "al-Waqi'ah",
                    "The Inevitable", "The Event",
                    "الواقعة", "سورة الواقعة");

        // Surah 57 - Al-Hadid
        putVariants(57, "Al-Hadid", "Hadid", "al-Hadid",
                    "The Iron", "The Iron (Al-Hadid)",
                    "الحديد", "سورة الحديد");

        // Surah 58 - Al-Mujadila
        putVariants(58, "Al-Mujadila", "Mujadila", "al-Mujadila",
                    "The Pleading Woman", "The Woman Who Pleads",
                    "المجادلة", "سورة المجادلة");

        // Surah 59 - Al-Hashr
        putVariants(59, "Al-Hashr", "Hashr", "al-Hashr",
                    "The Exile", "The Gathering", "The Banishment",
                    "الحشر", "سورة الحشر");

        // Surah 60 - Al-Mumtahanah
        putVariants(60, "Al-Mumtahanah", "Mumtahanah", "al-Mumtahanah",
                    "She that is to be examined", "The Tested Woman",
                    "الممتحنة", "سورة الممتحنة");

        // Surah 61 - As-Saff
        putVariants(61, "As-Saff", "Saff", "as-Saff",
                    "The Ranks", "The Row", "The Battle Array",
                    "الصف", "سورة الصف");

        // Surah 62 - Al-Jumu'ah
        putVariants(62, "Al-Jumu'ah", "Jumu'ah", "Jumuah", "al-Jumu'ah",
                    "The Congregation", "The Friday",
                    "الجمعة", "سورة الجمعة");

        // Surah 63 - Al-Munafiqun
        putVariants(63, "Al-Munafiqun", "Munafiqun", "al-Munafiqun",
                    "The Hypocrites", "The Hypocrites (Al-Munafiqun)",
                    "المنافقون", "سورة المنافقون");

        // Surah 64 - At-Taghabun
        putVariants(64, "At-Taghabun", "Taghabun", "at-Taghabun",
                    "The Mutual Disillusion", "The Mutual Loss and Gain",
                    "التغابن", "سورة التغابن");

        // Surah 65 - At-Talaq
        putVariants(65, "At-Talaq", "Talaq", "Talaq", "at-Talaq",
                    "The Divorce", "Divorce",
                    "الطلاق", "سورة الطلاق");

        // Surah 66 - At-Tahrim
        putVariants(66, "At-Tahrim", "Tahrim", "at-Tahrim",
                    "The Prohibition", "The Prohibition (At-Tahrim)",
                    "التحريم", "سورة التحريم");

        // Surah 67 - Al-Mulk
        putVariants(67, "Al-Mulk", "Mulk", "al-Mulk",
                    "The Sovereignty", "The Kingdom", "The Dominion",
                    "الملك", "سورة الملك");

        // Surah 68 - Al-Qalam
        putVariants(68, "Al-Qalam", "Qalam", "al-Qalam",
                    "The Pen", "The Pen (Al-Qalam)",
                    "القلم", "سورة القلم");

        // Surah 69 - Al-Haqqah
        putVariants(69, "Al-Haqqah", "Haqqah", "al-Haqqah",
                    "The Reality", "The Inevitable Reality",
                    "الحاقة", "سورة الحاقة");

        // Surah 70 - Al-Ma'arij
        putVariants(70, "Al-Ma'arij", "Ma'arij", "al-Ma'arij",
                    "The Ascending Stairways", "The Ways of Ascent",
                    "المعارج", "سورة المعارج");

        // Surah 71 - Nuh
        putVariants(71, "Nuh", "Nuh", "Noah", "Surah Nuh",
                    "نوح", "سورة نوح");

        // Surah 72 - Al-Jinn
        putVariants(72, "Al-Jinn", "Jinn", "al-Jinn",
                    "The Jinn", "The Jinn (Al-Jinn)", "Suratul Jinn",
                    "الجن", "سورة الجن");

        // Surah 73 - Al-Muzzammil
        putVariants(73, "Al-Muzzammil", "Muzzammil", "al-Muzzammil",
                    "The Enshrouded One", "The Bundled Up",
                    "المزمل", "سورة المزمل");

        // Surah 74 - Al-Muddaththir
        putVariants(74, "Al-Muddaththir", "Muddaththir", "al-Muddaththir",
                    "The Cloaked One", "The Enveloped",
                    "المدثر", "سورة المدثر");

        // Surah 75 - Al-Qiyamah
        putVariants(75, "Al-Qiyamah", "Qiyamah", "al-Qiyamah",
                    "The Resurrection", "The Rising of the Dead",
                    "القيامة", "سورة القيامة");

        // Surah 76 - Al-Insan
        putVariants(76, "Al-Insan", "Insan", "Ad-Dahr", "Dahr", "al-Insan",
                    "The Man", "The Human", "The Time",
                    "الانسان", "سورة الانسان", "الدهر");

        // Surah 77 - Al-Mursalat
        putVariants(77, "Al-Mursalat", "Mursalat", "al-Mursalat",
                    "The Emissaries", "The Winds Sent Forth",
                    "المرسلات", "سورة المرسلات");

        // Surah 78 - An-Naba
        putVariants(78, "An-Naba", "Naba", "an-Naba", "Al-Naba",
                    "The Tidings", "The Announcement", "The Great News",
                    "النبأ", "سورة النبأ");

        // Surah 79 - An-Nazi'at
        putVariants(79, "An-Nazi'at", "Nazi'at", "an-Nazi'at",
                    "Those who drag forth", "The Extractors",
                    "النازعات", "سورة النازعات");

        // Surah 80 - 'Abasa
        putVariants(80, "'Abasa", "Abasa", "He Frowned",
                    "عبس", "سورة عبس");

        // Surah 81 - At-Takwir
        putVariants(81, "At-Takwir", "Takwir", "at-Takwir",
                    "The Overthrowing", "The Folding Up",
                    "التكوير", "سورة التكوير");

        // Surah 82 - Al-Infitar
        putVariants(82, "Al-Infitar", "Infitar", "al-Infitar",
                    "The Cleaving", "The Breaking Apart",
                    "الانفطار", "سورة الانفطار");

        // Surah 83 - Al-Mutaffifin
        putVariants(83, "Al-Mutaffifin", "Mutaffifin", "al-Mutaffifin",
                    "The Defrauding", "The Cheaters",
                    "المطففين", "سورة المطففين");

        // Surah 84 - Al-Inshiqaq
        putVariants(84, "Al-Inshiqaq", "Inshiqaq", "al-Inshiqaq",
                    "The Splitting Open", "The Rending",
                    "الانشقاق", "سورة الانشقاق");

        // Surah 85 - Al-Buruj
        putVariants(85, "Al-Buruj", "Buruj", "al-Buruj",
                    "The Mansions of the Stars", "The Constellations",
                    "البروج", "سورة البروج");

        // Surah 86 - At-Tariq
        putVariants(86, "At-Tariq", "Tariq", "at-Tariq",
                    "The Nightcommer", "The Morning Star",
                    "الطارق", "سورة الطارق");

        // Surah 87 - Al-A'la
        putVariants(87, "Al-A'la", "A'la", "al-A'la", "Al-Ala",
                    "The Most High", "The Highest",
                    "الأعلى", "سورة الأعلى");

        // Surah 88 - Al-Ghashiyah
        putVariants(88, "Al-Ghashiyah", "Ghashiyah", "al-Ghashiyah",
                    "The Overwhelming", "The Pall", "The Overshadowing",
                    "الغاشية", "سورة الغاشية");

        // Surah 89 - Al-Fajr
        putVariants(89, "Al-Fajr", "Fajr", "al-Fajr",
                    "The Dawn", "The Daybreak",
                    "الفجر", "سورة الفجر");

        // Surah 90 - Al-Balad
        putVariants(90, "Al-Balad", "Balad", "al-Balad",
                    "The City", "The Land",
                    "البلد", "سورة البلد");

        // Surah 91 - Ash-Shams
        putVariants(91, "Ash-Shams", "Shams", "ash-Shams",
                    "The Sun", "The Sun (Ash-Shams)",
                    "الشمس", "سورة الشمس");

        // Surah 92 - Al-Layl
        putVariants(92, "Al-Layl", "Layl", "al-Layl",
                    "The Night", "The Night (Al-Layl)",
                    "الليل", "سورة الليل");

        // Surah 93 - Ad-Duhaa
        putVariants(93, "Ad-Duhaa", "Duhaa", "ad-Duhaa", "Ad-Duha",
                    "The Morning Hours", "The Morning Brightness",
                    "الضحى", "سورة الضحى");

        // Surah 94 - Ash-Sharh
        putVariants(94, "Ash-Sharh", "Sharh", "ash-Sharh", "Al-Inshirah",
                    "The Relief", "The Opening", "The Expansion",
                    "الشرح", "سورة الشرح");

        // Surah 95 - At-Tin
        putVariants(95, "At-Tin", "Tin", "at-Tin",
                    "The Fig", "The Fig (At-Tin)",
                    "التين", "سورة التين");

        // Surah 96 - Al-'Alaq
        putVariants(96, "Al-'Alaq", "'Alaq", "Alaq", "al-'Alaq", "Al-Alaq",
                    "The Clot", "The Clinging Clot",
                    "العلق", "سورة العلق");

        // Surah 97 - Al-Qadr
        putVariants(97, "Al-Qadr", "Qadr", "al-Qadr",
                    "The Power", "The Night of Decree", "The Night of Power",
                    "القدر", "سورة القدر");

        // Surah 98 - Al-Bayyinah
        putVariants(98, "Al-Bayyinah", "Bayyinah", "al-Bayyinah",
                    "The Clear Proof", "The Evidence",
                    "البينة", "سورة البينة");

        // Surah 99 - Az-Zalzalah
        putVariants(99, "Az-Zalzalah", "Zalzalah", "az-Zalzalah",
                    "The Earthquake", "The Quaking",
                    "الزلزلة", "سورة الزلزلة");

        // Surah 100 - Al-'Adiyat
        putVariants(100, "Al-'Adiyat", "'Adiyat", "Adiyat", "al-'Adiyat",
                    "The Courser", "The Steeds", "The Chargers",
                    "العاديات", "سورة العاديات");

        // Surah 101 - Al-Qari'ah
        putVariants(101, "Al-Qari'ah", "Qari'ah", "Qariah", "al-Qari'ah",
                    "The Calamity", "The Striking", "The Disaster",
                    "القارعة", "سورة القارعة");

        // Surah 102 - At-Takathur
        putVariants(102, "At-Takathur", "Takathur", "at-Takathur",
                    "The Rivalry in world increase", "The Competition", "The Piling Up",
                    "التكاثر", "سورة التكاثر");

        // Surah 103 - Al-'Asr
        putVariants(103, "Al-'Asr", "'Asr", "Asr", "al-'Asr",
                    "The Declining Day", "The Time", "The Epoch",
                    "العصر", "سورة العصر");

        // Surah 104 - Al-Humazah
        putVariants(104, "Al-Humazah", "Humazah", "al-Humazah",
                    "The Traducer", "The Slanderer", "The Gossipmonger",
                    "الهمزة", "سورة الهمزة");

        // Surah 105 - Al-Fil
        putVariants(105, "Al-Fil", "Fil", "al-Fil",
                    "The Elephant", "The Elephant (Al-Fil)",
                    "الفيل", "سورة الفيل");

        // Surah 106 - Quraysh
        putVariants(106, "Quraysh", "Quraish", "Quraysh",
                    "قريش", "سورة قريش");

        // Surah 107 - Al-Ma'un
        putVariants(107, "Al-Ma'un", "Ma'un", "al-Ma'un",
                    "The Small kindnesses", "The Acts of Kindness",
                    "الماعون", "سورة الماعون");

        // Surah 108 - Al-Kawthar
        putVariants(108, "Al-Kawthar", "Kawthar", "al-Kawthar",
                    "The Abundance", "The River in Paradise",
                    "الكوثر", "سورة الكوثر");

        // Surah 109 - Al-Kafirun
        putVariants(109, "Al-Kafirun", "Kafirun", "al-Kafirun",
                    "The Disbelievers", "The Unbelievers",
                    "الكافرون", "سورة الكافرون");

        // Surah 110 - An-Nasr
        putVariants(110, "An-Nasr", "Nasr", "an-Nasr",
                    "The Divine Support", "The Help", "The Victory",
                    "النصر", "سورة النصر");

        // Surah 111 - Al-Masad
        putVariants(111, "Al-Masad", "Masad", "al-Masad", "Al-Lahab",
                    "The Palm Fiber", "The Twisted Strands", "The Flame",
                    "المسد", "سورة المسد", "اللهب", "تبت");

        // Surah 112 - Al-Ikhlas
        putVariants(112, "Al-Ikhlas", "Ikhlas", "al-Ikhlas",
                    "The Sincerity", "The Fidelity", "The Purity",
                    "الإخلاص", "سورة الإخلاص");

        // Surah 113 - Al-Falaq
        putVariants(113, "Al-Falaq", "Falaq", "al-Falaq",
                    "The Daybreak", "The Dawn", "The Rising Dawn",
                    "الفلق", "سورة الفلق");

        // Surah 114 - An-Nas
        putVariants(114, "An-Nas", "Nas", "an-Nas",
                    "The Mankind", "The People", "The Men",
                    "الناس", "سورة الناس");
    }

    private static void putVariants(int surahNumber, String... variants) {
        for (String variant : variants) {
            // Store with various normalizations
            SURAH_MAP.put(normalize(variant), surahNumber);
            // Also store with "Surah " prefix if not already present
            if (!variant.toLowerCase().startsWith("surah")) {
                SURAH_MAP.put(normalize("Surah " + variant), surahNumber);
                SURAH_MAP.put(normalize("Suratul " + variant), surahNumber);
                SURAH_MAP.put(normalize("Surat " + variant), surahNumber);
                SURAH_MAP.put(normalize("Chapter " + variant), surahNumber);
            }
        }
    }

    /**
     * Resolves a surah name to its number.
     * Returns null if the name cannot be resolved.
     */
    public static Integer resolve(String surahName) {
        if (surahName == null || surahName.isEmpty()) {
            return null;
        }
        return SURAH_MAP.get(normalize(surahName));
    }

    /**
     * Checks if a string is a valid surah name.
     */
    public static boolean isSurahName(String text) {
        return resolve(text) != null;
    }

    /**
     * Normalizes a string for lookup: lowercases, removes diacritics, extra spaces.
     * Preserves Arabic characters for Arabic name matching.
     */
    private static String normalize(String input) {
        if (input == null) {
            return "";
        }

        // Check if input is Arabic
        boolean isArabic = input.matches(".*[\\u0600-\\u06FF\\u0750-\\u077F].*");

        if (isArabic) {
            // For Arabic: remove diacritics (harakat) and normalize alif variants
            String normalized = input;

            // Normalize alif variants to plain alif (ا)
            normalized = normalized.replace("\u0623", "\u0627"); // أ -> ا
            normalized = normalized.replace("\u0625", "\u0627"); // إ -> ا
            normalized = normalized.replace("\u0622", "\u0627"); // آ -> ا

            // Remove harakat (diacritics)
            normalized = normalized.replaceAll("[\\u064B-\\u065F\\u0670]", "");

            // Remove tatweel (stretching character) - don't replace with space
            normalized = normalized.replace("\u0640", "");

            // Remove hyphens and soft hyphens
            normalized = normalized.replaceAll("[-\\u00AD]", " ");

            // Normalize whitespace
            normalized = normalized.replaceAll("\\s+", " ").trim();
            return normalized;
        }

        // For English/transliterated names
        String normalized = input.toLowerCase();
        normalized = normalized.replaceAll("[-–—]+", " "); // Treat hyphenated and spaced names the same
        normalized = normalized.replaceAll("[^a-z0-9\\s]", ""); // Keep only alphanumeric and spaces
        // Normalize whitespace
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }
}
