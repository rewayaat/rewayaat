package com.rewayaat.loader;

/**
 * Asynchronously Loads volumes 1 - 7 of Al-Kafi into the database.
 */
public class AlKafiLoaderEnglish {

    public static void main(String[] args) throws Exception {

        /**
         * VOL 1
         */
        AlKafiWorker t1 = new AlKafiWorker(1, 846);
        t1.start();
        /**
         * VOL 2
         */
        AlKafiWorker t2 = new AlKafiWorker(847, 1772);
        t2.start();
        /**
         * VOL 3
         */
        AlKafiWorker t3 = new AlKafiWorker(1773, 2556);
        t3.start();
        /**
         * VOL 4
         */
        AlKafiWorker t4 = new AlKafiWorker(2557, 3366);
        t4.start();
        /**
         * VOL 5
         */
        AlKafiWorker t5 = new AlKafiWorker(3367, 4234);
        t5.start();
        /**
         * VOL 6
         */
        AlKafiWorker t6 = new AlKafiWorker(4235, 5089);
        t6.start();
        /**
         * VOL 7
         */
        AlKafiWorker t7 = new AlKafiWorker(5090, 5744);
        t7.start();

    }

}