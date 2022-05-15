package ml_6002b_coursework;

/**
 * Empty class for Part 2.1 of the coursework
 *
 */
public class AttributeMeasures {

    // Log base 10 to Log base 2 helper function, aimed to take fraction input e.g (4/5 or 0.8)
    public static double log2(double x){
        return Math.log(x)/Math.log(2);
    }

    // Calculate the total of a row in a table, takes array input
    public static int tableSum(int[][] table){
        int sum = 0;
        for(int[] row : table){
            for(int value: row){
                sum += value;
            }
        }
        return sum;
    }

    public static int attributeSum(int[] row){
        int sum = 0;
        for (int value : row){
            sum += value;
        }
        return sum;
    }

    public static int classSum(int[][] table, int columnIndex){
        int sum = 0;
        for(int[] row : table){
            sum += row[columnIndex];
        }
        return sum;
    }

    public static double rootEntropy(int[][] table){
        int tableFrequency = tableSum(table);
        double entropy = 0.0;
        for (int i=0; i<table[0].length; i++){
            double proportion = (double) classSum(table, i)/tableFrequency;
            if (proportion == 1.0 || proportion == 0.0){
                continue;
            }
            entropy += (proportion * log2(proportion));
        }
        return -entropy;
    }

    public static double rootImpurity(int[][] table){
        int totalFrequency = tableSum(table);
        double impurity = 0.0;
        for (int i=0; i<table[0].length; i++){
            double proportion = (double) classSum(table, i)/totalFrequency;
            if (proportion == 0.0){
                continue;
            }
            impurity += (proportion*proportion);
        }
        return 1-impurity;
    }

    public static double measureInformationGain(int[][] table){
        int[] rowTotal = new int[table.length];
        int tableFrequency = tableSum(table);
        double infoGainValue = 0.0;

        for(int i=0; i<table.length; i++){
            rowTotal[i] = attributeSum(table[i]);
            for(int value: table[i]){
                double proportion = (double)value/rowTotal[i];
                if (proportion == 1.0 | proportion == 0.0){
                    continue;
                }
                infoGainValue += -((double)rowTotal[i]/tableFrequency)*(proportion * log2(proportion));
            }
        }
        return rootEntropy(table) - infoGainValue;
    }

    public static double measureInformationGainRatio(int[][] table){
        int totalFrequency = tableSum(table);
        double splitInfo = 0.0;
        for (int[] row: table){
            double proportion = (double) attributeSum(row)/totalFrequency;
            splitInfo += -(proportion * log2(proportion));
        }
        return measureInformationGain(table)/splitInfo;
    }

    public static double measureGini(int[][] table) {
        int totalFrequency = tableSum(table);
        double impurity = 0.0;

        // For each row in the table
        for (int[] row: table) {
            double attributeImpurity = 0.0;
            // For each value in the row
            for (int value: row){
                double proportion = (double)value / attributeSum(row);
                attributeImpurity += proportion*proportion;
            }
            attributeImpurity = 1-attributeImpurity;
            impurity += attributeImpurity*((double)attributeSum(row)/totalFrequency);
        }
        return rootImpurity(table) - impurity;
    }

    public static double measureChiSquared(int[][] table) {
        int totalFrequency = tableSum(table);
        double chiValue = 0.0;
        for(int i=0; i<table.length; i++){
            for(int j=0; j<table[0].length; j++){
                double value = table[i][j];
                double expected = (attributeSum(table[i])*((double)classSum(table, j)/(double)totalFrequency));
                chiValue += ((value-expected)*(value-expected))/expected;
            }
        }
        return chiValue;
    }

    public static void main(String[] args){
        int[][] peaty = {{4,0}, {1,5}};
        //int[][] test_3x3_array = {{4,3,1},{1,3,4},{3,1,1}};
        System.out.println("Measure Information Gain for Peaty: " + measureInformationGain(peaty));
        System.out.println("Measure Information Gain Ratio for Peaty: " + measureInformationGainRatio(peaty));
        System.out.println("Measure Gini for Peaty: " + measureGini(peaty));
        System.out.println("Measure Chi Squared for Peaty: " + measureChiSquared(peaty));


    }

}
