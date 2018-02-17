package com.rewayaat;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.rewayaat.web.config.ClientProvider;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.RandomStringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;

/**
 * Refreshes the ES Index to pick up synonym filter updates.
 */
public class RefreshSynonymFilter {

    public static void refresh() throws Exception {

        // delete existing and download new synonym file from github
        File synonymFile = new File("/synonyms.txt");
        FileUtils.copyURLToFile(new URL("https://raw.githubusercontent.com/rewayaat/rewayaat/master/rewayaat/src/main/resources/synonyms.txt"), synonymFile);
        System.out.println("Successfully downloaded new synonyms file");

        // transfer new synonyms file to elastic search ec2 instance
        updateSynonymsFileOnESInstance(synonymFile);

        // get current index name
        HttpResponse resp = Unirest.get("http://" + ClientProvider.host + ":9200/_cat/indices").asString();
        String[] currIndexNames = ((String) resp.getBody()).split("\n");
        String currIndexName = "";
        for (String index : currIndexNames) {
            String indexName = index.split(" ")[2];
            if (indexName.startsWith("rewayaat")) {
                currIndexName = indexName;
                break;
            }
        }
        System.out.println(currIndexName);

        // generate new index name
        String newIndexName = "rewayaat_" + RandomStringUtils.randomAlphabetic(10).toLowerCase();
        System.out.println("Generated new index name: " + newIndexName);

        // copy analyzer, mappings and data to new index
        Runtime rt = Runtime.getRuntime();
        Process pr = rt.exec("elasticdump   --input=http://" + ClientProvider.host + ":9200/" + currIndexName + "   --output=http://" + ClientProvider.host + ":9200/" + newIndexName + "   --type=analyzer");
        pr.waitFor();
        System.out.println("Successfully copied original index analyzer");
        pr = rt.exec("elasticdump   --input=http://" + ClientProvider.host + ":9200/" + currIndexName + "   --output=http://" + ClientProvider.host + ":9200/" + newIndexName + "   --type=mapping");
        pr.waitFor();
        System.out.println("Successfully copied original index mappings");
        pr = rt.exec("elasticdump   --input=http://" + ClientProvider.host + ":9200/" + currIndexName + "   --output=http://" + ClientProvider.host + ":9200/" + newIndexName + "   --type=data");
        pr.waitFor();
        System.out.println("Successfully copied original index data");

        // remove alias to old index, add new alias
        Unirest.post("http://" + ClientProvider.host + ":9200/_aliases")
                .body("{ \"actions\" : [ { \"remove\" : { \"index\" : \"" + currIndexName + "\", \"alias\" : \"rewayaat\" } } ] }").asString();
        System.out.println("Successfully removed original index alias");

        Unirest.post("http://" + ClientProvider.host + ":9200/_aliases").body("{ \"actions\" : [ { \"add\" : { \"index\" : \"" + newIndexName + "\", \"alias\" : \"rewayaat\" } } ] }").asString();
        System.out.println("Successfully inserted new index alias to rewayaat");

        // delete old index
        Unirest.delete("http://" + ClientProvider.host + ":9200/" + currIndexName).asString();
        System.out.println("Successfully deleted old index");
    }

    public static void updateSynonymsFileOnESInstance(File fileToCopy) {
        String sftpHost = ClientProvider.host;
        int sftPort = 22;
        String sftpUser = "ec2-user";
        String sftpPass = System.getenv("ES_SSH_KEY");
        String sftpWorkingDir = System.getenv("ES_CONFIG_SYNONYM_DIR");

        Session session = null;
        Channel channel = null;
        ChannelSftp channelSftp = null;
        System.out.println("preparing the host information for sftp.");
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(sftpUser, sftpHost, sftPort);
            session.setPassword(sftpPass);
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect();
            System.out.println("Host connected.");
            channel = session.openChannel("sftp");
            channel.connect();
            System.out.println("sftp channel opened and connected.");
            channelSftp = (ChannelSftp) channel;
            channelSftp.cd(sftpWorkingDir);
            channelSftp.put(new FileInputStream(fileToCopy), fileToCopy.getName());
            System.out.println("File transfered successfully to host.");
        } catch (Exception ex) {
            System.out.println("Exception found while transfer the response.");
        } finally {
            channelSftp.exit();
            System.out.println("sftp Channel exited.");
            channel.disconnect();
            System.out.println("Channel disconnected.");
            session.disconnect();
            System.out.println("Host Session disconnected.");
        }
    }
}
