package com.rewayaat.loader;

/**
 * Asynchronously Loads volumes 1 - 7 of Al-Kafi into the database.
 */
public class AlKafiLoaderEnglish {

    public static void main(String[] args) throws Exception {

        /**
         * VOL 1
         */
        AlKafiWorker t11 = new AlKafiWorker(1, 196);
        t11.start();
        AlKafiWorker t13 = new AlKafiWorker(197, 391);
        t13.start();
        AlKafiWorker t12 = new AlKafiWorker(392, 624);
        t12.start();
        AlKafiWorker t14 = new AlKafiWorker(625, 846);
        t14.start();
        /**
         * VOL 2
         */
        AlKafiWorker t21 = new AlKafiWorker(847, 1086);
        t21.start();
        AlKafiWorker t22 = new AlKafiWorker(1087, 1363);
        t22.start();
        AlKafiWorker t23 = new AlKafiWorker(1364, 1772);
        t23.start();
        /**
         * VOL 3
         */
        AlKafiWorker t31 = new AlKafiWorker(1773, 1929);
        t31.start();
        AlKafiWorker t33 = new AlKafiWorker(1930, 2159);
        t33.start();
        AlKafiWorker t32 = new AlKafiWorker(2160, 2315);
        t32.start();
        AlKafiWorker t34 = new AlKafiWorker(2316, 2556);
        t34.start();
        /**
         * VOL 4
         */
        AlKafiWorker t41 = new AlKafiWorker(2557, 2723);
        t41.start();
        AlKafiWorker t43 = new AlKafiWorker(2724, 2925);
        t43.start();
        AlKafiWorker t42 = new AlKafiWorker(2926, 3110);
        t42.start();
        AlKafiWorker t44 = new AlKafiWorker(3111, 3366);
        t44.start();
        /**
         * VOL 5
         */
        AlKafiWorker t51 = new AlKafiWorker(3367, 3496);
        t51.start();
        AlKafiWorker t53 = new AlKafiWorker(3497, 3868);
        t53.start();
        AlKafiWorker t52 = new AlKafiWorker(3869, 3965);
        t52.start();
        AlKafiWorker t54 = new AlKafiWorker(3966, 4234);
        t54.start();
        /**
         * VOL 6
         */
        AlKafiWorker t61 = new AlKafiWorker(4235, 4407);
        t61.start();
        AlKafiWorker t64 = new AlKafiWorker(4408, 4611);
        t64.start();
        AlKafiWorker t62 = new AlKafiWorker(4612, 4789);
        t62.start();
        AlKafiWorker t63 = new AlKafiWorker(4790, 5089);
        t63.start();
        /**
         * VOL 7
         */
        AlKafiWorker t71 = new AlKafiWorker(5090, 5146);
        t71.start();
        AlKafiWorker t73 = new AlKafiWorker(5147, 5302);
        t73.start();
        AlKafiWorker t72 = new AlKafiWorker(5303, 5541);
        t72.start();
        AlKafiWorker t74 = new AlKafiWorker(5542, 5744);
        t74.start();
    }

}