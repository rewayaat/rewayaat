package com.rewayaat.loader.nahjulbalagha;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewayaat.web.config.ClientProvider;
import com.rewayaat.web.data.hadith.HadithObject;
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

public class NahjulBalaghWorker extends Thread {

    private PrintWriter writer;

    @Override
    public void run() {
        PrintWriter writer = null;
        try {
            writer = new PrintWriter((new BufferedWriter(new FileWriter(
                    "/home/zir0/git/rewayaatv2/rewayaat/src/main/java/com/rewayaat/loader/nahjulbalagha/operationLog.txt",
                    true))));
            this.writer = writer;
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        NodeList nList = null;
        try {
            String xmlLocation = "/home/zir0/git/rewayaatv2/rewayaat/src/main/java/com/rewayaat/loader/resources/nahjulbalagha.xml";
            File fXmlFile = new File(xmlLocation);
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(fXmlFile);
            doc.getDocumentElement().normalize();
            nList = doc.getElementsByTagName("Row");
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (int temp = 1; temp < 490 - 1; temp++) {

            try {
                int max = nList.getLength();
                Node nNode = nList.item(temp);
                NodeList subList = nNode.getChildNodes();
                String name = subList.item(1).getTextContent().trim();
                String arabic = subList.item(3).getTextContent().replaceAll("[`~``’`]", "").trim().replaceAll("\n", "")
                        .replace("\r", "");
                String number = String.valueOf(Integer.parseInt(name.substring(2)));
                String english = subList.item(5).getTextContent().replaceAll("[`~’]", "").trim().replaceAll("\n", "")
                        .replace("\r", "");
                String publisher = subList.item(9).getTextContent().trim();
                String notes = null;
                if (english.contains("ar-Radi says")) {
                    notes = english.substring(english.indexOf("ar-Radi says") - 10).trim().replaceAll("\n", "")
                            .replace("\r", "");
                    english = english.substring(0, english.length() - notes.length());
                    notes.replaceAll(" *(;&#10)* ", "");
                }

                english.replaceAll(" *(;&#10)* ", "");
                arabic.replaceAll(" *(;&#10)* ", "");
                writer.println("Values for current Row Node: " + nNode.getAttributes().getNamedItem("ss:Height"));
                writer.println("-----------------------");
                writer.println("Book : Nahj Al-Balagha");
                writer.println("Number : " + number);
                writer.println("Name : " + name);
                writer.println("Arabic : " + arabic);
                writer.println("English : " + english);
                writer.println("Publisher : " + publisher);
                writer.println("\n\n");
                HadithObject hadithobj = new HadithObject();
                hadithobj.setArabic(arabic);
                hadithobj.setEnglish(english.replaceAll("`~", ""));
                hadithobj.setBook("Nahj Al-Balagha");
                hadithobj.setNumber(number);
                hadithobj.setPublisher(publisher);
                if (notes != null) {
                    hadithobj.setNotes(notes);
                }
                saveHadith(hadithobj);
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