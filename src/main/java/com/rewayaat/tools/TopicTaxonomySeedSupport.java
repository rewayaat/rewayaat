package com.rewayaat.tools;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared taxonomy seed vocabulary used by both rule-based tagging and offline taxonomy audit tooling.
 */
final class TopicTaxonomySeedSupport {

    private static final Map<String, List<String>> EXTRA_SEEDS = Map.ofEntries(
            Map.entry("prayer", List.of("prayer", "pray", "salah", "salat", "ركعه", "ركعات", "صلاه", "صلاة")),
            Map.entry("congregational-prayer", List.of("congregational prayer", "group prayer", "jama ah", "jamaat", "صلاة الجماعة")),
            Map.entry("friday-prayer", List.of("friday prayer", "jumu ah", "jumuah", "جمعه", "جمعة")),
            Map.entry("call-to-prayer", List.of("call to prayer", "adhan", "iqamah", "اذان", "أذان", "اقامه", "إقامة")),
            Map.entry("night-prayer", List.of("night prayer", "tahajjud", "salat al layl", "صلاة الليل")),
            Map.entry("travel-prayer", List.of("travel prayer", "prayer during travel", "salat al musafir", "صلاة المسافر")),
            Map.entry("fasting", List.of("fasting", "fast", "sawm", "صوم", "صيام")),
            Map.entry("charity", List.of("charity", "alms", "صدقه", "صدقة")),
            Map.entry("zakat", List.of("zakat", "zakah", "زكاه", "زكاة")),
            Map.entry("khums", List.of("khums", "خمس")),
            Map.entry("hajj", List.of("hajj", "pilgrimage", "tawaf", "mina", "tashriq", "sacrificial offerings", "udhiyya", "hady", "حج")),
            Map.entry("umrah", List.of("umrah", "عمره", "عمرة")),
            Map.entry("ihram", List.of("ihram", "muhrim", "إحرام", "محرم", "permissible impermissible muhrim")),
            Map.entry("ziyarat", List.of("visiting the shrine", "pilgrimage to the shrine", "the ziyarah of the prophet", "performance of the ziyarah", "go to the ziyarah", "ziyarat of imam husayn", "زيارة الحسين", "زيارة امير المؤمنين", "زيارة المعصوم")),
            Map.entry("purification", List.of("purification", "ablution", "wudu", "janabah", "junub", "hayd", "nifas", "child birth", "childbirth", "وضوء", "جنابه", "جنابة", "جنب", "حيض", "نفاس", "طهاره", "طهارة")),
            Map.entry("ghusl", List.of("ghusl", "ritual bath", "bath of friday", "bath", "shower", "غسل")),
            Map.entry("water-purity", List.of("water purity", "water impurity", "purity and impurity of water", "pure water", "impure water", "quantity of water that always remains clean", "water from which animals beasts and birds have drunk", "water remains clean", "الماء", "طهارة الماء", "نجاسة الماء")),
            Map.entry("funeral-rites", List.of("funeral", "deceased", "janazah", "grave", "جنائز", "ميت", "جنازة")),
            Map.entry("washing-deceased", List.of("washing deceased", "washing of the deceased", "wash the deceased", "ghusl al mayyit", "غسل الميت", "تغسيل الميت")),
            Map.entry("touching-deceased", List.of("touching deceased", "touching the deceased", "touch the deceased", "mass al mayyit", "مس الميت")),
            Map.entry("grave-visitation", List.of("visiting graves", "visit graves", "grave visitation", "ziyarat al qubur", "زيارة القبور", "قبور")),
            Map.entry("dua", List.of("supplication", "invocation", "supplications", "دعاء")),
            Map.entry("remembrance", List.of("dhikr", "tasbih", "morning and evening", "morning and evening remembrance", "اذكار", "أذكار", "تسبيح")),
            Map.entry("mosque", List.of("mosque", "mosques", "masjid", "مسجد", "مساجد")),
            Map.entry("quran", List.of("quran", "qur an", "qur'an", "القران", "القرآن")),
            Map.entry("knowledge", List.of("knowledge", "learning", "علم")),
            Map.entry("wisdom", List.of("wisdom", "حكمه", "حكمة")),
            Map.entry("faith", List.of("faith", "belief", "iman", "exclusively for believers", "ايمان", "إيمان", "للمؤمنين خاصة")),
            Map.entry("tawhid", List.of("tawhid", "divine unity", "unity", "god is one and only", "belief in oneness of allah", "oneness of allah", "there is no god except allah", "there is no god but god", "la ilaha illallah", "description of allah", "توحيد", "لا اله الا الله", "وصف الله")),
            Map.entry("prophethood", List.of("prophethood", "messengerhood", "nubuwwa", "نبوه", "نبوة")),
            Map.entry("divine-decree", List.of("divine decree", "predestination", "qadar", "qada", "قضاء", "قدر")),
            Map.entry("imamate", List.of("imamate", "imamah", "divine leadership", "people of dhikr", "ahl al dhikr", "people of remembrance", "إمامه", "إمامة", "اهل الذكر", "أهل الذكر")),
            Map.entry("wilayah", List.of("wilayah", "wilaya", "walayah", "divine authority", "shi a of ali", "your shi a", "the path urged to be maintained steadfastly", "ولاية", "هذا الأمر", "هذا الامر")),
            Map.entry("occultation", List.of("occultation", "ghayba", "disappearance", "disappearance of the expected imam", "public sight", "غيبه", "غيبة")),
            Map.entry("twelve-imams", List.of("twelve imams", "twelve caliphs", "divine leaders after the prophet", "الائمه الاثنا عشر", "الائمة الاثنا عشر", "الاثنا عشر")),
            Map.entry("imam-mahdi", List.of("imam mahdi", "mahdi", "qa im", "qaim", "expected imam", "twelfth imam", "master of the age", "sahib al zaman", "المهدي", "القائم")),
            Map.entry("reappearance-signs", List.of("signs preceding the appearance", "signs preceding the reappearance", "signs of reappearance", "signs preceding appearance", "علامات الظهور")),
            Map.entry("afterlife", List.of("afterlife", "hereafter", "الاخره", "الآخرة")),
            Map.entry("resurrection", List.of("resurrection", "day of reckoning", "قيامه", "قيامة")),
            Map.entry("paradise", List.of("paradise", "جنة", "الجنه", "jannah")),
            Map.entry("hellfire", List.of("hellfire", "fire of hell", "النار", "جهنم")),
            Map.entry("repentance", List.of("repentance", "repent", "توبه", "توبة")),
            Map.entry("patience", List.of("patience", "steadfastness", "صبر")),
            Map.entry("trials-afflictions", List.of("afflictions", "calamities", "trials", "distress", "severity of a believer s afflictions", "مصائب", "بلاء", "ابتلاء")),
            Map.entry("gratitude", List.of("gratitude", "thankfulness", "شكر")),
            Map.entry("sincerity", List.of("sincerity", "ikhlas", "اخلاص", "إخلاص")),
            Map.entry("intention", List.of("intention", "نية", "نيه")),
            Map.entry("asceticism", List.of("asceticism", "zuhd", "renunciation", "زهد")),
            Map.entry("trust-in-god", List.of("trust in god", "rely on allah", "tawakkul", "توكل")),
            Map.entry("justice", List.of("justice", "عدل")),
            Map.entry("oppression", List.of("oppression", "injustice", "tyranny", "ظلم")),
            Map.entry("truthfulness", List.of("truthfulness", "truthful", "صدق")),
            Map.entry("forgiveness", List.of("forgiveness", "forgive", "عفو", "يغفر")),
            Map.entry("mercy", List.of("mercy", "رحمه", "رحمة")),
            Map.entry("humility", List.of("humility", "humble", "vanity", "arrogance", "تواضع", "كبر", "تكبر")),
            Map.entry("good-character", List.of("good character", "character", "akhlaq", "خلق", "الخلق")),
            Map.entry("family", List.of("family", "household", "اسره", "أسرة")),
            Map.entry("parents", List.of("parents", "mother", "father", "والدين", "والدان", "ام", "أب")),
            Map.entry("children", List.of("children", "ولد", "اولاد", "أولاد")),
            Map.entry("brotherhood", List.of("brotherhood", "brother in faith", "brotherhood of islam", "fellow believer", "rights of a believer on his brother", "اخوه", "أخوة")),
            Map.entry("neighbors", List.of("neighbors", "neighbor", "جار", "جيران")),
            Map.entry("marriage", List.of("marriage", "wife", "husband", "nikah", "زواج")),
            Map.entry("temporary-marriage", List.of("temporary marriage", "mut ah", "muta", "متعه", "متعة")),
            Map.entry("divorce", List.of("divorce", "طلاق")),
            Map.entry("mourning-condolence", List.of("condolence", "mourning", "lamentation", "grief", "mourning gatherings", "العزاء", "المواساة", "ماتم")),
            Map.entry("leadership", List.of("leadership", "leader", "قياده", "قيادة")),
            Map.entry("governance", List.of("governance", "governor", "ruler", "governing", "malik al ashtar", "response to muawiya", "in response to muawiya", "حكم")),
            Map.entry("rights", List.of("rights", "right due", "rights of a believer", "against a fellow believer", "whose testimony must be rejected", "حقوق", "حق")),
            Map.entry("halal", List.of("halal", "haram", "lawful", "unlawful", "حلال", "حرام")),
            Map.entry("food-drink", List.of("food", "drink", "eating", "drinking", "اطعمه", "أطعمة", "شراب")),
            Map.entry("dress-adornment", List.of("dress", "garment", "adornment", "لباس", "زينة")),
            Map.entry("livelihood", List.of("livelihood", "earnings", "profits", "professions", "كسب")),
            Map.entry("trade", List.of("trade", "sales", "commerce", "sell", "buy", "currency exchange", "تجاره", "تجارة", "بيع", "شراء")),
            Map.entry("professions", List.of("professions", "occupation", "crafts", "trades", "المهن", "الحرف")),
            Map.entry("usury", List.of("usury", "riba", "ربا", "الربا")),
            Map.entry("debt-loans", List.of("debt", "loan", "debt and loan", "debtor", "creditor", "دين", "قرض")),
            Map.entry("inheritance", List.of("inheritance", "ميراث", "تركة")),
            Map.entry("endowments-gifts", List.of("endowment", "charitable endowment", "gift", "present", "waqf", "هبه", "هبة", "وقف")),
            Map.entry("oaths-vows", List.of("oaths", "vows", "expiations", "يمين", "نذر", "كفارة")),
            Map.entry("expiation", List.of("expiation", "kaffara", "atonement", "كفارة")),
            Map.entry("testimony-judgment", List.of("testimony", "judgment", "judiciary", "whose testimony must be rejected", "whose testimony must be accepted", "شهادة", "قضاء")),
            Map.entry("penalties", List.of("legal punishment", "stoning", "execution", "hudud", "tazir", "حدود", "تعزير")),
            Map.entry("hunting-slaughter", List.of("hunting", "hunting and slaughtering", "slaughtering", "slaughter", "صيد", "ذبائح", "ذبح")),
            Map.entry("prophet-muhammad", List.of("messenger of allah", "prophet muhammad", "holy prophet", "birth of the holy prophet", "his demise", "رسول الله", "النبي محمد", "محمد صلى الله عليه وآله")),
            Map.entry("ahl-al-bayt", List.of("ahl al bayt", "ahl al-bayt", "اهل البيت", "أهل البيت", "العتره", "العترة")),
            Map.entry("imam-kazim", List.of("imam musa kadhim", "imam musa al-kazim", "musa ibn jafar", "الكاظم", "موسى بن جعفر")),
            Map.entry("imam-ridha", List.of("imam ridha", "imam al-ridha", "ali ibn musa al-ridha", "traditions from al ridha", "الرضا", "علي بن موسى الرضا")),
            Map.entry("imam-ali", List.of("imam ali", "ali ibn abi talib", "amir al muminin", "o ali", "ya ali", "about those who accused him of killing uthman", "response to muawiya", "امير المؤمنين", "أمير المؤمنين", "يا علي", "علي بن ابي طالب")),
            Map.entry("imam-askari", List.of("imam hasan askari", "imam al-askari", "الحسن العسكري", "العسكري")),
            Map.entry("fatimah", List.of("fatimah", "fatima", "zahra", "فاطمه", "فاطمة", "الزهراء")),
            Map.entry("imam-hasan", List.of("imam hasan", "for hasan", "to hasan", "للحسن", "الحسن", "hasan ibn ali")),
            Map.entry("imam-husayn", List.of("imam husayn", "husayn ibn ali", "الحسين", "كربلاء", "karbala")),
            // Phase 1: New tags for Quranic context feature (79 new primary + 1 secondary)
            Map.entry("disbelief", List.of("disbelief", "kufr", "unbelief", "كفر", "كافر", "الكفر", "الكافرين")),
            Map.entry("lying", List.of("lying", "falsehood", "lie", "كذب", "الكذب", "كاذب")),
            Map.entry("guidance-misguidance", List.of("guidance", "misguidance", "astray", "هدى", "ضلال", "الضالين")),
            Map.entry("taqwa", List.of("taqwa", "piety", "god-consciousness", "muttaqin", "تقوى", "المتقين")),
            Map.entry("creation", List.of("creation", "created", "creatures", "signs of god", "خلق", "الخلق", "المخلوقات")),
            Map.entry("musa", List.of("musa", "moses", "موسى", "النبي موسى", "كليم الله")),
            Map.entry("soul", List.of("soul", "nafs", "self", "نفس", "النفس")),
            Map.entry("death", List.of("death", "dying", "موت", "الموت", "الوفاة")),
            Map.entry("pharaoh", List.of("pharaoh", "fir'awn", "فرعون")),
            Map.entry("wealth-materialism", List.of("wealth", "materialism", "riches", "mammon", "مال", "المال", "الثراء", "الغنى")),
            Map.entry("love", List.of("love", "affection", "حب", "المحبة")),
            Map.entry("witnesses", List.of("witnesses", "testimony", "martyrs", "shahada", "شهادة", "شهود", "شهداء")),
            Map.entry("fear-of-god", List.of("fear of god", "khashya", "reverence", "خشية", "خشية الله", "يخشون")),
            Map.entry("promise-threat", List.of("promise", "threat", "warning", "وعد", "وعيد", "الوعد والوعيد")),
            Map.entry("ibrahim", List.of("ibrahim", "abraham", "إبراهيم", "النبي إبراهيم", "خليل الله")),
            Map.entry("satan-iblis", List.of("satan", "iblis", "devil", "shaitan", "الشيطان", "إبليس")),
            Map.entry("light-darkness", List.of("light", "darkness", "nur", "ظلام", "نور", "النور")),
            Map.entry("christians-jews", List.of("christians", "jews", "people of the book", "ahl al-kitab", "نصارى", "يهود", "أهل الكتاب")),
            Map.entry("previous-nations", List.of("previous nations", "ancient peoples", "الأمم السابقة", "الأمم الخالية")),
            Map.entry("nuh", List.of("nuh", "noah", "نوح", "النبي نوح")),
            Map.entry("prostration", List.of("prostration", "sujud", "سجود", "سجدة", "يسجدون")),
            Map.entry("women", List.of("women", "woman", "female", "نساء", "امرأة", "النساء")),
            Map.entry("arrogance", List.of("arrogance", "pride", "kibr", "كبر", "تكبر", "المتكبرين")),
            Map.entry("angels", List.of("angels", "angel", "ملائكة", "ملك", "الملائكة")),
            Map.entry("covenant", List.of("covenant", "promise", "mithaq", "عهد", "العهد", "ميثاق")),
            Map.entry("signs-of-god", List.of("signs of god", "ayat", "verses", "آيات", "آية")),
            Map.entry("unseen", List.of("unseen", "ghayb", "invisible", "غيب", "الغيب", "العالم الغيبي")),
            Map.entry("enemies", List.of("enemies", "enemy", "adversaries", "أعداء", "العدو")),
            Map.entry("corruption", List.of("corruption", "mischief", "fasad", "فساد", "مفسدة")),
            Map.entry("poor-needy", List.of("poor", "needy", "destitute", "فقراء", "مساكين", "الفقراء")),
            Map.entry("enjoining-good", List.of("enjoining good", "forbidding evil", "amr bil ma'ruf", "الأمر بالمعروف", "النهي عن المنكر")),
            Map.entry("spending", List.of("spending", "charity", "infaq", "إنفاق", "الإنفاق في سبيل الله")),
            Map.entry("warfare-jihad", List.of("warfare", "jihad", "fighting", "جهاد", "قتال", "القتال")),
            Map.entry("community", List.of("community", "ummah", "nation", "أمة", "الأمة", "جماعة")),
            Map.entry("glorification", List.of("glorification", "praise", "tasbih", "تسبيح", "سبح", "يسبحون")),
            Map.entry("mockery-ridicule", List.of("mockery", "ridicule", "scoffing", "سخرية", "استهزاء")),
            Map.entry("relatives", List.of("relatives", "kinship", "kindred", "أقارب", "الأقارب", "ذي القربى")),
            Map.entry("security-safety", List.of("security", "safety", "aman", "peace", "أمن", "الأمن", "السلامة")),
            Map.entry("secret", List.of("secret", "secrets", "concealment", "سر", "أسرار", "السر")),
            Map.entry("jinn", List.of("jinn", "jinns", "جن", "الجن")),
            Map.entry("parable", List.of("parable", "example", "amthal", "like", "أمثال", "مثل")),
            Map.entry("children-of-israel", List.of("children of israel", "israelites", "بنو إسرائيل", "بني إسرائيل")),
            Map.entry("isa", List.of("isa", "jesus", "عيسى", "النبي عيسى", "المسيح")),
            Map.entry("magic-sorcery", List.of("magic", "sorcery", "sihr", "سحر", "السحر")),
            Map.entry("intellect", List.of("intellect", "reason", "aql", "understanding", "عقل", "العقل", "أهل العقل")),
            Map.entry("slavery-captives", List.of("slavery", "captives", "slaves", "رق", "عبيد", "أسرى")),
            Map.entry("obscenity", List.of("obscenity", "indecency", "fahisha", "فحشاء", "فاحشة")),
            Map.entry("scriptures", List.of("scriptures", "books", "torah", "gospel", "كتب", "الكتب السماوية", "التوراة", "الإنجيل")),
            Map.entry("intercession", List.of("intercession", "shafa'a", "شفاعة", "الشفاعة")),
            Map.entry("greed", List.of("greed", "stinginess", "shuhh", "greed", "شح", "بخل")),
            Map.entry("throne-of-god", List.of("throne of god", "arsh", "kursi", "عرش", "العرش", "كرسي")),
            Map.entry("equality", List.of("equality", "equal", "مساواة", "السواء", "تساووا")),
            Map.entry("shirk", List.of("shirk", "polytheism", "idolatry", "شرك", "مشركين", "الأصنام")),
            Map.entry("maryam", List.of("maryam", "mary", "mariam", "مريم", "العذراء")),
            Map.entry("heedlessness", List.of("heedlessness", "negligence", "ghaflah", "غفلة", "الغافلين")),
            Map.entry("orphans", List.of("orphans", "orphan", "يتامى", "اليتيم", "الأيتام")),
            Map.entry("revelation", List.of("revelation", "wahy", "inspiration", "وحي", "الوحي")),
            Map.entry("lut", List.of("lut", "lot", "لوط", "النبي لوط")),
            Map.entry("apostasy", List.of("apostasy", "riddah", "ردة", "الردة", "مرتد")),
            Map.entry("boasting", List.of("boasting", "showing off", "riya", "رياء", "الرياء")),
            Map.entry("migration", List.of("migration", "hijra", "هجرة", "المهاجرين")),
            Map.entry("adam", List.of("adam", "آدم", "النبي آدم", "أبو البشر")),
            Map.entry("sulayman", List.of("sulayman", "solomon", "سليمان", "النبي سليمان", "داود")),
            Map.entry("dawud", List.of("dawud", "david", "داود", "النبي داود")),
            Map.entry("anger", List.of("anger", "rage", "ghadab", "غضب", "الغضب", "يغضب")),
            Map.entry("yusuf", List.of("yusuf", "joseph", "يوسف", "النبي يوسف")),
            Map.entry("sacrifice", List.of("sacrifice", "offering", "qurbani", "ذبح", "قربان", "الأضحية")),
            Map.entry("backbiting-slander", List.of("backbiting", "slander", "ghiba", "buhtan", "غيبة", "بهتان", "الغيبة")),
            Map.entry("chastity", List.of("chastity", "modesty", "iffah", "عفة", "العفة")),
            Map.entry("hypocrisy", List.of("hypocrisy", "hypocrites", "nifaq", "نفاق", "المنافقين")),
            Map.entry("regret", List.of("regret", "remorse", "nadama", "ندم", "الندم")),
            Map.entry("betrayal", List.of("betrayal", "treachery", "khiyanah", "خيانة", "الخيانة")),
            Map.entry("envy", List.of("envy", "hasad", "jealousy", "حسد", "الحسد", "الحاسد")),
            Map.entry("ayyub", List.of("ayyub", "job", "أيوب", "النبي أيوب")),
            Map.entry("despair", List.of("despair", "hopelessness", "ya's", "يأس", "القنوط")),
            Map.entry("consultation", List.of("consultation", "shura", "شورى", "الشورى", "مشاورة")),
            Map.entry("adoption", List.of("adoption", "adopted", "تبني", "التبني")),
            Map.entry("ramadan", List.of("ramadan", "رمضان", "شهر رمضان")),
            Map.entry("karbala", List.of("karbala", "كربلاء", "مأساة كربلاء"))
    );

    private static final Set<String> HEADING_ONLY_SLUGS = Set.of(
            "ahl-al-bayt",
            "prophet-muhammad",
            "imam-kazim",
            "imam-ridha",
            "imam-askari",
            "fatimah",
            "imam-husayn",
            "imam-mahdi",
            // Phase 1: New prophet/biography tags
            "musa",
            "ibrahim",
            "nuh",
            "isa",
            "lut",
            "adam",
            "sulayman",
            "dawud",
            "yusuf",
            "ayyub",
            "maryam",
            "pharaoh"
    );

    private static final Set<String> SPECIALIZED_SEED_ONLY_SLUGS = Set.of(
            "children",
            "dress-adornment",
            "food-drink",
            "ghusl",
            "hellfire",
            "knowledge",
            "leadership",
            "livelihood",
            "mercy",
            "night-prayer",
            "testimony-judgment",
            "ziyarat",
            "remembrance"
    );

    private TopicTaxonomySeedSupport() {
    }

    static List<String> extraSeeds(String slug) {
        if (slug == null || slug.isBlank()) {
            return List.of();
        }
        return EXTRA_SEEDS.getOrDefault(slug, List.of());
    }

    static Map<String, List<String>> extraSeedsBySlug() {
        return EXTRA_SEEDS;
    }

    static Set<String> headingOnlySlugs() {
        return HEADING_ONLY_SLUGS;
    }

    static boolean useDefaultLiteralSeeds(String slug) {
        return slug != null && !slug.isBlank() && !SPECIALIZED_SEED_ONLY_SLUGS.contains(slug);
    }

    static int minimumSuggestionScore(String slug) {
        if (slug == null || slug.isBlank()) {
            return 2;
        }
        return switch (slug) {
            case "hellfire" -> 4;
            case "children",
                 "dress-adornment",
                 "food-drink",
                 "ghusl",
                 "knowledge",
                 "leadership",
                 "livelihood",
                 "mercy",
                 "night-prayer",
                 "remembrance",
                 "testimony-judgment" -> 3;
            default -> 2;
        };
    }

    static List<String> refineSuggestedTags(String book,
                                            String chapter,
                                            String english,
                                            String arabic,
                                            List<String> tags,
                                            Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }

        LinkedHashSet<String> adjusted = new LinkedHashSet<>();
        for (String tag : tags) {
            String normalized = TopicTaxonomySupport.normalizeSlug(tag);
            if (!normalized.isBlank()) {
                adjusted.add(normalized);
            }
        }
        if (adjusted.isEmpty()) {
            return List.of();
        }

        String headingEnglish = " " + TopicTaxonomySupport.normalizeEnglishForMatch(
                ((book == null ? "" : book) + " " + (chapter == null ? "" : chapter)).trim()) + " ";
        String headingArabic = " " + TopicTaxonomySupport.normalizeArabicForMatch(
                ((book == null ? "" : book) + " " + (chapter == null ? "" : chapter)).trim()) + " ";
        String combinedEnglish = " " + TopicTaxonomySupport.normalizeEnglishForMatch(
                ((book == null ? "" : book) + " " + (chapter == null ? "" : chapter) + " " + abbreviate(english, 1400)).trim()) + " ";
        String combinedArabic = " " + TopicTaxonomySupport.normalizeArabicForMatch(
                ((book == null ? "" : book) + " " + (chapter == null ? "" : chapter) + " " + abbreviate(arabic, 900)).trim()) + " ";

        boolean ziyaratContext = containsAny(combinedEnglish,
                " ziyarah ", " ziyarat ", " performance of the ziyarah ", " go to the ziyarah ",
                " visit the prophet ", " visiting the prophet ", " visiting the holy prophet ", " visitors of husain ");
        boolean prophetContext = containsAny(combinedEnglish,
                " prophet ", " messenger of allah ", " holy prophet ")
                || containsAny(combinedArabic, " النبي ", " رسول الله ");
        boolean funeralContext = containsAny(combinedEnglish,
                " funeral ", " deceased ", " janazah ", " burial ", " bury ", " washing deceased ", " touching deceased ")
                || containsAny(combinedArabic, " جنازة ", " ميت ", " دفن ");
        if (ziyaratContext) {
            adjusted.add("ziyarat");
            if (prophetContext) {
                adjusted.add("prophet-muhammad");
            }
            if (adjusted.contains("funeral-rites") && !funeralContext) {
                adjusted.remove("funeral-rites");
            }
        }

        boolean tawhidContext = containsAny(combinedEnglish,
                " there is no god but ", " there is no god except ", " one god ", " tawhid ",
                " unity ", " description of allah ")
                || containsAny(combinedArabic, " لا اله الا الله ", " توحيد ", " وصف الله ");
        if (tawhidContext) {
            adjusted.add("tawhid");
            adjusted.remove("good-character");
            if (combinedEnglish.contains(" al tawhid ")
                    || containsAny(combinedEnglish, " there is no god but ", " one god ")) {
                adjusted.remove("knowledge");
            }
        }

        if (adjusted.contains("prayer")
                && (adjusted.contains("purification")
                || adjusted.contains("ghusl")
                || adjusted.contains("water-purity")
                || adjusted.contains("fasting")
                || adjusted.contains("zakat"))
                && !containsAny(headingEnglish,
                " prayer ", " salah ", " salat ", " friday prayer ", " congregational prayer ",
                " call to prayer ", " mosque ")
                && !containsAny(headingArabic, " صلاة ", " الجمعه ", " الجمعة ", " اذان ", " أذان ", " مسجد ")) {
            adjusted.remove("prayer");
        }

        if (adjusted.contains("imamate")) {
            adjusted.remove("leadership");
        }
        boolean imamateContext = containsAny(combinedEnglish,
                " divine leadership ", " divine leader ", " rank of the divine leader ", " concept of divine leadership ")
                || containsAny(combinedArabic, " الامامة ", " إمامة ", " امامة ");
        if (imamateContext) {
            adjusted.add("imamate");
            adjusted.remove("leadership");
        }

        boolean ummAlKitabContext = containsAny(combinedEnglish,
                " mother of the book ", " umm al kitab ", " umm al kitab ");
        if (ummAlKitabContext) {
            adjusted.remove("parents");
            adjusted.remove("family");
            adjusted.remove("knowledge");
        }

        if (containsAny(combinedEnglish, " instructions for hasan ", " for hasan ", " to hasan ")
                || containsAny(combinedArabic, " للحسن ")) {
            adjusted.add("imam-hasan");
            adjusted.add("good-character");
            adjusted.remove("imam-ali");
            adjusted.remove("governance");
            adjusted.remove("leadership");
        }

        if (adjusted.contains("quran")
                && containsAny(combinedEnglish, " reciting the quran ", " reciting verses ", " surat ", " verses by night ")) {
            adjusted.remove("knowledge");
        }

        if (adjusted.contains("knowledge")
                && adjusted.contains("quran")
                && (adjusted.contains("imamate") || adjusted.contains("wilayah"))
                && (containsAny(headingEnglish,
                " leadership with divine authority ", " people of dhikr ",
                " enlightening points deduced from the holy quran ")
                || containsAny(combinedEnglish, " wilayah ", " wilaya ", " people of dhikr ", " guardian "))) {
            adjusted.remove("knowledge");
        }

        if (adjusted.contains("imamate")
                && adjusted.contains("remembrance")
                && containsAny(combinedEnglish, " people of dhikr ", " people of remembrance ", " ahl al dhikr ")
                && !containsAny(combinedEnglish, " tasbih ", " morning and evening ", " dhikr after prayer ")
                && !containsAny(combinedArabic, " تسبيح ", " اذكار ", " أذكار ")) {
            adjusted.remove("remembrance");
        }

        if (adjusted.contains("faith")
                && (adjusted.contains("rights") || adjusted.contains("brotherhood"))
                && containsAny(combinedEnglish,
                " rights of a believer ", " believer on his brother ", " believing brother ", " brother in belief ")
                && !containsAny(combinedEnglish,
                " faith increases ", " faith decreases ", " degree of faith ", " pillars of faith ",
                " foundations of faith ", " no faith ", " people of faith ", " iman ")
                && !containsAny(combinedArabic, " إيمان ", " ايمان ")) {
            adjusted.remove("faith");
        }

        if (adjusted.contains("wilayah")
                && (adjusted.contains("occultation") || adjusted.contains("imam-mahdi"))
                && !containsAny(combinedEnglish,
                " wilayah ", " wilaya ", " walayah ", " guardian ", " this matter ", " this affair ")
                && !containsAny(combinedArabic, " ولاية ", " هذا الامر ", " هذا الأمر ")) {
            adjusted.remove("wilayah");
        }

        if ((adjusted.contains("family") || adjusted.contains("parents"))
                && (adjusted.contains("neighbors")
                || adjusted.contains("rights")
                || adjusted.contains("imamate")
                || adjusted.contains("occultation")
                || adjusted.contains("ziyarat"))
                && !containsAny(headingEnglish,
                " family ", " parents ", " father ", " mother ", " children ",
                " progeny ", " descendants ", " near of kin ", " household ")
                && !containsAny(headingArabic,
                " اسره ", " أسرة ", " والدين ", " اب ", " أب ", " ام ", " أم ",
                " ذريه ", " ذرية ", " نسل ", " اهل البيت ", " أهل البيت ")) {
            adjusted.remove("family");
            adjusted.remove("parents");
        }

        if (adjusted.contains("imam-ali")
                && adjusted.contains("tawhid")
                && !containsAny(headingEnglish,
                " ali ", " amir al muminin ", " commander of the faithful ", " siffin ", " jamal ",
                " virtues of ali ", " manaqib ali ", " response to muawiya ")
                && !containsAny(headingArabic, " علي ", " امير المؤمنين ", " أمير المؤمنين ", " صفين ", " جمل ")) {
            adjusted.remove("imam-ali");
        }

        if (adjusted.contains("imam-ali")
                && (adjusted.contains("imam-mahdi") || adjusted.contains("occultation"))
                && !containsAny(headingEnglish,
                " ali ", " amir al muminin ", " commander of the faithful ", " virtues of ali ")
                && !containsAny(headingArabic, " علي ", " امير المؤمنين ", " أمير المؤمنين ")) {
            adjusted.remove("imam-ali");
        }

        if (adjusted.contains("leadership")
                && (adjusted.contains("rights")
                || adjusted.contains("neighbors")
                || adjusted.contains("good-character")
                || adjusted.contains("asceticism"))
                && !containsAny(headingEnglish,
                " leadership ", " leader ", " rulers ", " ruler ", " governor ", " governors ")
                && !containsAny(headingArabic, " قياده ", " قيادة ", " قائد ", " حكام ", " حاكم ")) {
            adjusted.remove("leadership");
        }

        if (adjusted.contains("tawhid")
                && adjusted.contains("faith")
                && !containsAny(combinedEnglish, " faith ", " belief ", " believers ", " iman ")
                && !containsAny(combinedArabic, " إيمان ", " ايمان ", " للمؤمنين خاصة ")) {
            adjusted.remove("faith");
        }

        List<String> refined = List.copyOf(adjusted);
        return taxonomyBySlug == null || taxonomyBySlug.isEmpty()
                ? refined
                : pruneAncestors(refined, taxonomyBySlug);
    }

    private static List<String> pruneAncestors(List<String> tags,
                                               Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        List<String> pruned = new java.util.ArrayList<>();
        for (String candidate : tags) {
            boolean hasMoreSpecific = false;
            for (String other : tags) {
                if (candidate.equals(other)) {
                    continue;
                }
                String parent = parentOf(other, taxonomyBySlug);
                while (!parent.isBlank()) {
                    if (candidate.equals(parent)) {
                        hasMoreSpecific = true;
                        break;
                    }
                    parent = parentOf(parent, taxonomyBySlug);
                }
                if (hasMoreSpecific) {
                    break;
                }
            }
            if (!hasMoreSpecific && !pruned.contains(candidate)) {
                pruned.add(candidate);
            }
        }
        return List.copyOf(pruned);
    }

    private static String parentOf(String slug,
                                   Map<String, TopicTaxonomySupport.TopicTaxonomyEntry> taxonomyBySlug) {
        TopicTaxonomySupport.TopicTaxonomyEntry entry = taxonomyBySlug.get(slug);
        return entry == null ? "" : entry.parentSlug();
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null || needles.length == 0) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars);
    }

    static boolean looksArabic(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(raw.charAt(i));
            if (block == Character.UnicodeBlock.ARABIC
                    || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                    || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B) {
                return true;
            }
        }
        return false;
    }
}
