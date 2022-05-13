package ml_6002b_coursework;

/**
 * Empty class for Part 2.1 of the coursework
 *
 */
public class AttributeMeasures {

    // Log base 10 to Log base 2 helper function
    public static double log2(double x){
        return Math.log(x)/Math.log(2);
    }

    public static double infoGain(double x){
        return -((x * log2(x) + ((1-x)*log2(1-x))));
    }
    public static double measureInformationGain(int[][] table){
        int[] rowTotal = new int[2];
        int totalFrequency = 0, totalOccurrences = 0;
        double[] infoGainValues = new double[2];
        for (int i=0; i<table.length; i++){
            // Take row total and calculate proportion for log calculation
            rowTotal[i] = table[i][0] + table[i][1];
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
            System.out.println(table[i][0] + ", " + table[i][1] + ", " + totalFrequency + ", tempval=" + proportion);
        }
        return infoGain((double)totalOccurrences/totalFrequency) - infoGainValues[0] - infoGainValues[1];
    }

    public static double measureInformationGainRatio(int[][] table){
        int totalFrequency = 0;
        double[] splitInfoValues = new double[2];
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
        int[][] test_array = {{4,0},{1,5}};
        //System.out.println("Measure Information Gain for Peaty: " + measureInformationGain(test_array));
        System.out.println("Measure Information Gain Ratio for Peaty: " + measureInformationGainRatio(test_array));
        //System.out.println("Measure Chi Squared for Peaty: " + measureChiSquared());
        //System.out.println("Measure Gini for Peaty: " + measureGini());

    }

}
