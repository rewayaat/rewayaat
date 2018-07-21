package com.rewayaat;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.rewayaat.config.ClientProvider;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;

/**
 * Refreshes the ES Index to pick up synonym filter updates.
 */
public class RefreshSynonymFilter {

    /**
     * The file we will eventually overwrite the existing synonyms file with.
     */
    private static String synonymsLocalFile = "/synonyms.txt";
    private static Logger log = Logger.getLogger(RefreshSynonymFilter.class.getName(), new LoggerFactory() {
        @Override
        public Logger makeNewLoggerInstance(String name) {
            return new RewayaatLogger(name);
        }
    });

    public static void refresh() throws Exception {

        if (System.getenv("SPRING_PROFILES_ACTIVE").equalsIgnoreCase("prod")) {
            // delete existing and download new synonym file from github
            File synonymFile = new File(synonymsLocalFile);
            FileUtils.copyURLToFile(new URL("https://raw.githubusercontent.com/rewayaat/rewayaat/master/rewayaat/src/main/resources/synonyms.txt"), synonymFile);
            log.info("Successfully downloaded new synonyms file");

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

            if (!currIndexName.equals("")) {
                log.info("Current index name is: " + currIndexName);
                // create backup of current index at /var/lib/docker/devicemapper/mnt/b43a07171255604a89772f76ed043b5a40dcb5331a84bcc9e410e48ee50ac747/rootfs/rewayaat_backup.json
                Runtime rt = Runtime.getRuntime();
                Process pr = rt.exec("elasticdump   --input=http://" + ClientProvider.host + ":9200/" + currIndexName + "   --output=/rewayaat_backup.json   --type=data");
                pr.waitFor();
                log.info("Successfully backed up original index data");
            } else {
                log.info("No existing rewayaat index found!");
            }

            // generate new index name
            String newIndexName = "rewayaat_" + RandomStringUtils.randomAlphabetic(10).toLowerCase();
            log.info("Generated new index name: " + newIndexName);

            // copy analyzer, mappings and data to new index
            Process pr = Runtime.getRuntime().exec("elasticdump   --input=http://" + ClientProvider.host + ":9200/" + currIndexName + "   --output=http://" + ClientProvider.host + ":9200/" + newIndexName + "   --type=analyzer");
            pr.waitFor();
            log.info("Successfully copied original index analyzer");
            pr = Runtime.getRuntime().exec("elasticdump   --input=http://" + ClientProvider.host + ":9200/" + currIndexName + "   --output=http://" + ClientProvider.host + ":9200/" + newIndexName + "   --type=mapping");
            pr.waitFor();
            log.info("Successfully copied original index mappings");
            pr = Runtime.getRuntime().exec("elasticdump   --input=http://" + ClientProvider.host + ":9200/" + currIndexName + "   --output=http://" + ClientProvider.host + ":9200/" + newIndexName + "   --type=data");
            pr.waitFor();
            log.info("Successfully copied original index data");

            // remove alias to old index, add new alias
            Unirest.post("http://" + ClientProvider.host + ":9200/_aliases")
                    .body("{ \"actions\" : [ { \"remove\" : { \"index\" : \"" + currIndexName + "\", \"alias\" : \"rewayaat\" } } ] }").asString();
            log.info("Successfully removed original index alias");

            Unirest.post("http://" + ClientProvider.host + ":9200/_aliases").body("{ \"actions\" : [ { \"add\" : { \"index\" : \"" + newIndexName + "\", \"alias\" : \"rewayaat\" } } ] }").asString();
            log.info("Successfully inserted new index alias to rewayaat");

            // delete old index
            Unirest.delete("http://" + ClientProvider.host + ":9200/" + currIndexName).asString();
            log.info("Successfully deleted old index");
        } else {
            log.info("Not refreshing Elastic Search Index, this does not look like a prod environment.");
        }
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
        log.info("preparing the host information for sftp.");
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(sftpUser, sftpHost, sftPort);
            session.setPassword(sftpPass);
            log.info("SSH creds: " + sftpUser + ", " + sftpHost + ", " + sftPort + ", " + sftpPass);
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect();
            log.info("Host connected.");
            channel = session.openChannel("sftp");
            channel.connect();
            log.info("sftp channel opened and connected.");
            channelSftp = (ChannelSftp) channel;
            channelSftp.cd(sftpWorkingDir);
            try (InputStream io = new FileInputStream(fileToCopy)) {
                channelSftp.put(io, fileToCopy.getName());
            }
            log.info("File transfered successfully to host.");
        } catch (Exception ex) {
            log.error("Exception found while transfer the response.", ex);
        } finally {
            channelSftp.exit();
            log.info("sftp Channel exited.");
            channel.disconnect();
            log.info("Channel disconnected.");
            session.disconnect();
            log.info("Host Session disconnected.");
        }
    }
}
