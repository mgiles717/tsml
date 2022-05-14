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

    public static int rowSum(int[] row){
        int sum = 0;
        for (int value : row){
            sum += value;
        }
        return sum;
    }

    public static int columnSum(int[][] table, int columnIndex){
        int sum = 0;
        for(int[] row : table){
            sum += row[columnIndex];
        }
        return sum;
    }

    public static double rootEntropy(int[][] table){
        int tableFrequency = tableSum(table);
        double entropy = 0.0;
        for (int[] row : table){
            double proportion = (double)rowSum(row)/tableFrequency;
            if (proportion == 1.0 || proportion == 0.0){
                continue;
            }
            entropy += (proportion * log2(proportion));
        }
        return -entropy;
    }

    public static double infoGain(double x){
        return -((x * log2(x) + ((1-x)*log2(1-x))));
    }

    public static double measureInformationGain(int[][] table){
        int[] rowTotal = new int[table.length];
        int tableFrequency = tableSum(table);
        double infoGainValue = 0.0;

        for(int i=0; i<table.length; i++){
            rowTotal[i] = rowSum(table[i]);
            for(int value: table[i]){
                double proportion = (double)value/rowTotal[i];
                //System.out.println("Value: " + value + ", RowTotal: " + rowTotal[i] + ", Proportion: " + proportion);

                if (proportion == 1.0 || proportion == 0.0){
                    continue;
                }
                infoGainValue += -((double)rowTotal[i]/tableFrequency)*(proportion * log2(proportion));
            }
        }
        return rootEntropy(table) - (infoGainValue);
    }
    /*
    public static double measureInformationGain(int[][] table){
        int[] rowTotal = new int[table.length];
        int totalFrequency = 0, totalOccurrences = 0;
        double[] infoGainValues = new double[table.length];
        for (int i=0; i<table.length; i++){
            // Take row total and calculate proportion for log calculation
            for (int j=0; j<table[i].length; j++){
                rowTotal[i] += table[i][j];
            }
            totalOccurrences += table[i][0];
            double proportion = (double) table[i][0]/rowTotal[i];

            // Count the total frequency of occurrences in the table
            totalFrequency += rowTotal[i];

            // When using log2 with a value of 1, the value is NaN or 0; therefore, we should skip this iteration
            if (proportion == 1.0){
                infoGainValues[i] = 0.0;
                continue;
            }
            infoGainValues[i] = infoGain(proportion);
            System.out.println(rowTotal[0]);
        }
        return infoGain((double)totalOccurrences/totalFrequency) - infoGainValues[0] - infoGainValues[1];
    }
     */

    public static double measureInformationGainRatio(int[][] table){
        int totalFrequency = 0;
        double[] splitInfoValues = new double[table.length];
        double gainValue = measureInformationGain(table);
        for (int i=0; i<table.length; i++){
            splitInfoValues[i] = table[i][0];
            totalFrequency += table[i][0] + table[i][1];
        }
        System.out.println(splitInfoValues[0] + ", " + splitInfoValues[1]);
        splitInfoValues[0] = -(splitInfoValues[0]/totalFrequency)*log2(splitInfoValues[0]/totalFrequency);
        splitInfoValues[1] = -(splitInfoValues[1]/totalFrequency)*log2(splitInfoValues[1]/totalFrequency);
        //return splitInfoValues[0];
        return (gainValue/(splitInfoValues[0]+splitInfoValues[1]));
    }

    public static double measureGini(int[][] table){
        return 0;
    }

    public static double measureChiSquared(int[][] table){
        return 0;
    }

    public static void main(String[] args){
        int[][] peaty = {{4,0}, {1,5}};
        int[][] test_array = {{4,1,2},{3,1,1},{1,3,4}};
        //System.out.println(tableSum(test_array));
        //System.out.println(rootEntropy(test_array));
        System.out.println("Measure Information Gain for Peaty: " + measureInformationGain(test_array));
        //System.out.println("Measure Information Gain Ratio for Peaty: " + measureInformationGainRatio(test_array));
        //System.out.println("Measure Chi Squared for Peaty: " + measureChiSquared());
        //System.out.println("Measure Gini for Peaty: " + measureGini());

    }

}
