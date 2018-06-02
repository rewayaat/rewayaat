package com.rewayaat.loader.oldhdp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.config.ClientProvider;
import com.rewayaat.core.data.HadithObject;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class OldHDPWorker extends Thread {

    private int start;
    private int end;
    private PrintWriter writer;

    public OldHDPWorker(int start, int end) {
        this.start = start;
        this.end = end;
    }

    @Override
    public void run() {
        PrintWriter writer = null;
        try {
            writer = new PrintWriter((new BufferedWriter(new FileWriter(
                    "/home/zir0/git/rewayaatv2/rewayaat/src/main/java/com/rewayaat/loader/oldhdp/resources/operationLog_"
                            + start + "-" + end + ".txt",
                    true))));
            this.writer = writer;
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        NodeList nList = null;
        try {
            String xmlLocation = "/home/zir0/git/rewayaatv2/rewayaat/src/main/java/com/rewayaat/loader/oldhdp/resources/oldhdp.xml";
            File fXmlFile = new File(xmlLocation);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(fXmlFile);
            doc.getDocumentElement().normalize();
            nList = doc.getElementsByTagName("Row");
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (int temp = start; temp < end; temp++) {

            try {
                int max = nList.getLength();
                Node nNode = nList.item(temp);
                NodeList subList = nNode.getChildNodes();
                if (subList.item(1).getTextContent().startsWith("MH")) {
                    // some hadith don't have the write number of entries which
                    // messes us up, we will just skip over them.
                    if (subList.getLength() > 10) {
                        // this is a mizanul hikma hadith
                        String name = subList.item(1).getTextContent();
                        String topic = subList.item(3).getTextContent().replaceAll("( +)", " ").trim()
                                .replaceAll("[`~``’`]", "");
                        String subtopic = subList.item(5).getTextContent().replaceAll("( +)", " ").trim()
                                .replaceAll("[`~``’`]", "");
                        String arabic = subList.item(6).getTextContent().replaceAll("[`~``’`]", "");
                        String number = String.valueOf(Integer.parseInt(name.substring(2)));
                        String english = subList.item(7).getTextContent().replaceAll("[`~’]", "");
                        String primarySource = subList.item(8).getTextContent().replaceAll("v\\. ", "volume:")
                                .replaceAll("ch\\. ", "chapter:").replaceAll("no\\. ", "number:")
                                .replaceAll("[`~``’`]", "");
                        String publisher = subList.item(10).getTextContent();
                        writer.println(
                                "Values for current Row Node: " + nNode.getAttributes().getNamedItem("ss:Height"));
                        writer.println("-----------------------");
                        writer.println("Book : Mizan Al-Hikmah");
                        writer.println("Source : " + primarySource);
                        writer.println("Number : " + number);
                        writer.println("Name : " + name);
                        writer.println("Topic : " + topic);
                        writer.println("Sub-Topic : " + subtopic);
                        writer.println("Arabic : " + arabic);
                        writer.println("English : " + english);
                        writer.println("Publisher : " + publisher);
                        writer.println("\n\n");
                        HadithObject hadithobj = new HadithObject();
                        hadithobj.setArabic(arabic);
                        hadithobj.setEnglish(english.replaceAll("`~", ""));
                        hadithobj.setBook("Mizan Al-Hikmah");
                        hadithobj.setSource(primarySource);
                        hadithobj.setNumber(number);
                        hadithobj.setPart(topic);
                        hadithobj.setSection(subtopic);
                        hadithobj.setPublisher(publisher);
                        saveHadith(hadithobj);
                    }
                } else {
                    // this is a Ghurur al Hakim hadith...
                    String name = subList.item(1).getTextContent();
                    String topic = subList.item(3).getTextContent().replaceAll("( +)", " ").trim()
                            .replaceAll("[`~``’`]", "") + " " + subList.item(5).getTextContent();
                    String arabic = "الإمامُ عليٌّ (عَلَيهِ الّسَلامُ) : " + subList.item(6).getTextContent().replaceAll("[`~``’`]", "");
                    String number = String.valueOf(Integer.parseInt(name.substring(2)));
                    String english = "Imam Ali (AS) said, " + subList.item(7).getTextContent().replaceAll("[`~’]", "");
                    String primarySource = "Ghurar Al-Hikam";
                    String publisher = "Ansariyan Publications";
                    writer.println("Values for current Row Node: " + nNode.getAttributes().getNamedItem("ss:Height"));
                    writer.println("-----------------------");
                    writer.println("Book : " + primarySource);
                    writer.println("Number : " + number);
                    writer.println("Name : " + name);
                    writer.println("Topic : " + topic);
                    writer.println("Arabic : " + arabic);
                    writer.println("English : " + english);
                    writer.println("Publisher : " + publisher);
                    writer.println("\n\n");
                    HadithObject hadithobj = new HadithObject();
                    hadithobj.setArabic(arabic);
                    hadithobj.setEnglish(english.replaceAll("`~", ""));
                    hadithobj.setBook(primarySource);
                    hadithobj.setNumber(number);
                    hadithobj.setSection(topic);
                    hadithobj.setPublisher(publisher);
                    saveHadith(hadithobj);
                }
                writer.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        writer.close();
    }

    public void saveHadith(HadithObject obj) {

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
                // TODO Auto-generated catch block
                e.printStackTrace();
                writer.println(e);
                writer.flush();
                tries++;
            }
        }
        writer.println("Did not successfully write this hadith to the database!");

    }
}