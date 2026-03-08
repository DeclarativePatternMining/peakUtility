package org.cp26.huim.examples;

import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.search.strategy.Search;
import org.chocosolver.solver.variables.BoolVar;
import org.chocosolver.solver.variables.IntVar;
import org.cp26.huim.global.HUIMGlobalConstraint;
import org.cp26.huim.global.ProjectionBackend;
import org.cp26.huim.parser.HighUtilityDataset;

import java.util.ArrayList;
import java.util.List;

/**
 * Classe de test simple pour lancer HUIMGlobalConstraint directement depuis IntelliJ.
 * Vous pouvez configurer les paramètres dans la méthode main() et lancer avec Ctrl+Shift+F10.
 */
public final class HUIMGlobalExample {

    public static void main(String[] args) throws Exception {
        // ==================== CONFIGURATION ====================
        // Modifiez ces paramètres selon vos besoins

        String datasetPath = "data/foodmart.txt";     // Chemin du dataset
        int minUtil = 10000;                            // Seuil de minutil

        // Contraintes optionnelles
        String includeItems = "";                      // ex: "1,5,10" ou vide
        String excludeItems = "";                      // ex: "2,3" ou vide
        int minSize = 0;                               // Taille minimale du itemset (0 = pas de limite)
        int maxSize = -1;                              // Taille maximale du itemset (-1 = pas de limite)
        int maxUtil = -1;                              // Utilité maximale (-1 = pas de limite)
        ProjectionBackend projectionBackend = ProjectionBackend.ARRAY; // ARRAY or BITSET

        // Filtres sur les transactions
        int tuMin = Integer.MIN_VALUE;                 // Filtrer par transactionUtility minimal
        int tuMax = Integer.MAX_VALUE;                 // Filtrer par transactionUtility maximal

        // Options d'affichage et de sauvegarde
        boolean verbose = false;                        // Afficher les détails en console
        boolean saveToFile = false;                     // Sauvegarder les itemsets en fichier
        String outputFilePath = "huim_results.txt";    // Chemin du fichier de sortie

        // ========================================================

        System.out.println("=== HUIMGlobalExample ===");
        System.out.println("Dataset: " + datasetPath);
        System.out.println("MinUtil: " + minUtil);
        System.out.println();

        // Chargement des données
        if (verbose) System.out.println("Chargement des données...");
        HighUtilityDataset data = HighUtilityDataset.loadFromFile(datasetPath);
        if (verbose) System.out.println("✓ Données chargées");
        if (verbose) System.out.println("  - Nombre de transactions: " + data.transactionCount);
        if (verbose) System.out.println("  - Nombre d'items: " + data.itemCount);
        if (verbose) System.out.println();

        // Appliquer le filtre transactionUtility directement
        for (int t = 0; t < data.transactionCount; t++) {
            int tu = data.transactionUtility[t];
        }

        // Création du modèle CP
        if (verbose) System.out.println("Création du modèle CP...");
        Model model = new Model("HUIM-GLOBAL-TEST");
        BoolVar[] x = model.boolVarArray("X", data.itemCount);

        // Post de la contrainte globale HUIM
        if (verbose) System.out.println("✓ Postage de HUIMGlobalConstraint...");
        model.post(new HUIMGlobalConstraint(x, data, minUtil, maxUtil, projectionBackend));

        // Contraintes include/exclude
        if (!includeItems.isEmpty()) {
            int[] includeIds = parseCsvIds(includeItems);
            for (int id : includeIds) {
                Integer idx = data.originalItemIdToIndex.get(id);
                if (idx != null) {
                    model.arithm(x[idx], "=", 1).post();
                    if (verbose) System.out.println("  - Item " + id + " INCLUS");
                }
            }
        }

        if (!excludeItems.isEmpty()) {
            int[] excludeIds = parseCsvIds(excludeItems);
            for (int id : excludeIds) {
                Integer idx = data.originalItemIdToIndex.get(id);
                if (idx != null) {
                    model.arithm(x[idx], "=", 0).post();
                    if (verbose) System.out.println("  - Item " + id + " EXCLU");
                }
            }
        }

        // Contraintes de taille
        if (minSize > 0 || maxSize > 0) {
            IntVar card = model.intVar("card", 0, data.itemCount);
            model.sum(x, "=", card).post();
            if (minSize > 0) {
                model.arithm(card, ">=", minSize).post();
                if (verbose) System.out.println("  - Taille minimale: " + minSize);
            }
            if (maxSize > 0) {
                model.arithm(card, "<=", maxSize).post();
                if (verbose) System.out.println("  - Taille maximale: " + maxSize);
            }
        }

        // Contrainte d'utilité maximale (optionnel)
        if (maxUtil > 0) {
            List<BoolVar> yLits = new ArrayList<>();
            List<Integer> coeffs = new ArrayList<>();

            for (int t = 0; t < data.transactionCount; t++) {
                int[] items = data.transactionItems[t];
                int[] utils = data.transactionUtilities[t];

                for (int k = 0; k < items.length; k++) {
                    int item = items[k];
                    int u = utils[k];

                    BoolVar y = model.boolVar("y_" + t + "_" + k);
                    model.arithm(y, "<=", x[item]).post();

                    yLits.add(y);
                    coeffs.add(u);
                }
            }

            if (!yLits.isEmpty()) {
                BoolVar[] yArr = yLits.toArray(new BoolVar[0]);
                int[] cArr = coeffs.stream().mapToInt(Integer::intValue).toArray();
                model.scalar(yArr, cArr, "<=", maxUtil).post();
                if (verbose) System.out.println("  - Utilité maximale: " + maxUtil);
            }
        }

        System.out.println();
        if (verbose) System.out.println("Lancement de la résolution...");
        if (verbose) System.out.println("(Appuyez sur Ctrl+C pour arrêter)");
        System.out.println();

        // Configuration du solver et stratégie de recherche
        Solver solver = model.getSolver();
        solver.setSearch(Search.inputOrderUBSearch(x));

        // Préparation de la sauvegarde en fichier si nécessaire
        java.io.FileWriter fileWriter = null;
        if (saveToFile) {
            fileWriter = new java.io.FileWriter(outputFilePath);
            fileWriter.write("=== HUIM Results ===\n");
            fileWriter.write("Dataset: " + datasetPath + "\n");
            fileWriter.write("MinUtil: " + minUtil + "\n");
            fileWriter.write("\n");
            fileWriter.flush();
        }

        // Résolution
        long count = 0;
        long start = System.nanoTime();
        long peakMem = usedMemBytes();

        while (solver.solve()) {
            count++;
            long cur = usedMemBytes();
            if (cur > peakMem) peakMem = cur;

            // Afficher et sauvegarder les solutions trouvées
            if (saveToFile || verbose) {
                StringBuilder sb = new StringBuilder();
                sb.append("Solution ").append(count).append(": {");
                int util = 0;
                for (int i = 0; i < data.itemCount; i++) {
                    if (x[i].getValue() == 1) {
                        if (util > 0) sb.append(", ");
                        int itemId = data.itemIndexToOriginalId[i];
                        sb.append(itemId);
                        util++;
                    }
                }
                sb.append("} - Size: ").append(util);

                // Calculer l'utilité totale
                int totalUtil = 0;
                for (int t = 0; t < data.transactionCount; t++) {
                    int[] items = data.transactionItems[t];
                    int[] utils = data.transactionUtilities[t];
                    int txUtil = 0;
                    for (int k = 0; k < items.length; k++) {
                        if (x[items[k]].getValue() == 1) {
                            txUtil += utils[k];
                        }
                    }
                    totalUtil += txUtil;
                }
                sb.append(" - Utility: ").append(totalUtil);

                String result = sb.toString();

                if (verbose) {
                    System.out.println(result);
                }
                if (saveToFile && fileWriter != null) {
                    fileWriter.write(result + "\n");
                    fileWriter.flush();
                }
            }

            // Afficher la progression toutes les 1000 solutions
            if (verbose && count % 1000 == 0 && count > 0) {
                System.out.println("  ... " + count + " solutions trouvées");
            }
        }

        if (fileWriter != null) {
            fileWriter.close();
        }

        long ms = (System.nanoTime() - start) / 1_000_000L;
        double peakMB = peakMem / (1024.0 * 1024.0);

        // Résultats
        System.out.println();
        System.out.println("=== RÉSULTATS ===");
        System.out.println("Nombre de HUIs trouvés: " + count);
        System.out.println("Temps d'exécution: " + ms + " ms");
        System.out.println("Mémoire maximale utilisée: " + String.format("%.2f", peakMB) + " MB");
        if (saveToFile) {
            System.out.println("Résultats sauvegardés dans: " + outputFilePath);
        }
        System.out.println();
        System.out.println("Résolution terminée ✓");
    }

    /**
     * Parses une chaîne de valeurs CSV séparées par des virgules en tableau d'entiers.
     */
    private static int[] parseCsvIds(String s) {
        if (s == null || s.trim().isEmpty()) return new int[0];
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        int k = 0;
        for (String p : parts) {
            String q = p.trim();
            if (!q.isEmpty()) out[k++] = Integer.parseInt(q);
        }
        return (k == out.length) ? out : java.util.Arrays.copyOf(out, k);
    }

    /**
     * Retourne la mémoire utilisée en bytes.
     */
    private static long usedMemBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
