package com.rewayaat.loader.Mujam_al_Ahadith_al_Mutabara;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ClientProvider;
import com.rewayaat.core.data.HadithObject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
<<<<<<< HEAD
import org.apache.commons.lang3.StringUtils;
=======
import org.apache.commons.lang.StringUtils;
>>>>>>> c858816f79227837fd39ba664a1c576aa395c511

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;

public class MujamLoader {

    public static void main(String[] args) throws IOException {
        Reader in = new FileReader("/home/zir0/git/rewayaatv2/rewayaat/src/main/java/com/rewayaat/loader/resources/mujam.csv");
        Iterable<CSVRecord> records = CSVFormat.EXCEL.parse(in);
        for (CSVRecord record : records) {
            String book = "A Comprehensive Compilation of Reliable Narrations | معجم الاحاديث المعتبرة";
            String chapter = StringUtils.replaceOnce(record.get(0).trim(), ".", " -");
            String note = record.get(1).trim();
            String english = record.get(3).trim();
            if (!english.contains("[") || !english.contains("]")) {
                System.out.println(english);
            }
            String number = english.substring(english.indexOf("["), english.indexOf("]") + 1);
            english = english.substring(english.indexOf("]") + 1);
            String arabic = record.get(4).trim();
            arabic = arabic.replace("]" + number + "]", "");
            arabic = arabic.substring(arabic.indexOf("]") + 1).trim();
            english = english.replaceAll("al-", "Al-");
            note = note.replaceAll("al-", "Al-").replaceAll("`", "").replaceAll("’", "");
            ;
            chapter = chapter.replaceAll("al-", "Al-");
            english = english.replaceAll("`", "").replaceAll("’", "");
            chapter = chapter.replaceAll("\n", "").replaceAll("`", "").replaceAll("’", "");
            String section = record.get(5);
            String source = english.substring(0, english.indexOf(":")).trim();
            english = english.replace(":" + source, "").trim();
            english = english.replaceAll("\\(([0-9]+):([0-9]+)\\).*", "[$1:$2]");
            note = note.replaceAll("\\(([0-9]+):([0-9]+)\\).*", "[$1:$2]");
            note.replaceAll("-->", "").trim();
            if (english.contains(":")) {
                english = english.substring(english.indexOf(":") + 1).trim();
            }
            HadithObject hadithObject = new HadithObject();
            hadithObject.setSource(source);
            hadithObject.setChapter(chapter);
            hadithObject.setBook(book);
            hadithObject.setSection(section);
            hadithObject.setNumber(number);
            hadithObject.setEnglish(english);
            hadithObject.setArabic(arabic);
            if (note != null && !note.isEmpty()) {
                hadithObject.setNotes(note);
            }
            saveHadith(hadithObject);
        }
    }

    public static void saveHadith(HadithObject obj) {
        int tries = 0;
        while (tries < 5) {
            ObjectMapper mapper = new ObjectMapper();
            byte[] json;
            try {
                json = mapper.writeValueAsBytes(obj);
                ClientProvider.instance().getClient().prepareIndex(ClientProvider.INDEX, ClientProvider.TYPE)
                        .setSource(json).get();
                return;
            } catch (Exception e) {
                e.printStackTrace();
                tries++;
            }
        }
    }
}