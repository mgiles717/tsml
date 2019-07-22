/*
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package timeseriesweka.classifiers.dictionary_based;

import java.security.InvalidParameterException;
import java.util.*;

import net.sourceforge.sizeof.SizeOf;
import timeseriesweka.classifiers.MemoryContractable;
import timeseriesweka.classifiers.hybrids.cote.HiveCoteModule;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import utilities.*;
import utilities.samplers.*;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.functions.GaussianProcesses;
import weka.core.*;
import evaluation.storage.ClassifierResults;
import timeseriesweka.classifiers.AbstractClassifierWithTrainingInfo;
import timeseriesweka.classifiers.Checkpointable;

import static utilities.InstanceTools.resampleTrainAndTestInstances;
import static utilities.multivariate_tools.MultivariateInstanceTools.*;
import static weka.core.Utils.sum;
import timeseriesweka.classifiers.TrainTimeContractable;

/**
 * BOSS classifier with parameter search and ensembling for univariate and
 * multivariate time series classification.
 * If parameters are known, use the nested class BOSSIndividual and directly provide them.
 *
 * Options to change the method of ensembling to randomly select parameters instead of searching.
 * Has the capability to contract train time and checkpoint when using a random ensemble.
 *
 * Alphabetsize fixed to four and maximum wordLength of 16.
 *
 * @author James Large, updated by Matthew Middlehurst
 *
 * Implementation based on the algorithm described in getTechnicalInformation()
 */
public class BOSS extends AbstractClassifierWithTrainingInfo implements HiveCoteModule, TrainAccuracyEstimate, TrainTimeContractable, MemoryContractable, Checkpointable, TechnicalInformationHandler {

    private ArrayList<Double>[] paramAccuracy;
    private ArrayList<Double>[] paramTime;
    private ArrayList<Double>[] paramMemory;

    private int ensembleSize = 50;
    private int seed = 0;
    private int ensembleSizePerChannel = -1;
    private Random rand;
    private boolean randomEnsembleSelection = false;
    private boolean randomCVAccEnsemble = false;
    private boolean useCAWPE = false;

    private boolean useFastTrainEstimate = false;
    private int maxEvalPerClass = -1;
    private int maxEval = 500;

    private double maxWinLenProportion = 1;
    private double maxWinSearchProportion = 0.25;

    private boolean reduceTrainInstances = false;
    private double trainProportion = -1;
    private int maxTrainInstances = 1000;
    private boolean stratifiedSubsample = false;

    private boolean cutoff = false;
    private boolean loadAndFinish = false;

    private transient LinkedList<BOSSIndividual>[] classifiers;
    private int numSeries;
    private int[] numClassifiers;
    private int currentSeries = 0;
    private boolean isMultivariate = false;

    private final int[] wordLengths = { 16, 14, 12, 10, 8 };
    private final int[] alphabetSize = { 4 };
    private final double correctThreshold = 0.92;
    private int maxEnsembleSize = 500;

    private boolean bayesianParameterSelection = false;
    private int initialRandomParameters = 20;
    private int[] initialParameterCount;
    private Instances[] parameterPool;
    private Instances[] prevParameters;

    private String checkpointPath;
    private String serPath;
    private boolean checkpoint = false;
    private long checkpointTime = 0;
    private long checkpointTimeDiff = 0;
    private boolean cleanupCheckpointFiles = true;

    private long contractTime = 0;
    private boolean trainTimeContract = false;
    private boolean underContractTime = false;

    private long memoryLimit = 0;
    private long bytesUsed = 0;
    private boolean memoryContract = false;
    private boolean underMemoryLimit = true;

    //RBOSS CV acc variables, stored as field for checkpointing.
    private int[] classifiersBuilt;
    private int[] lowestAccIdx;
    private double[] lowestAcc;

    private String trainCVPath;
    private boolean trainCV = false;

    private transient Instances train;
    private double ensembleCvAcc = -1;
    private double[] ensembleCvPreds = null;

    private int numThreads = 1;
    private boolean multiThread = false;

    protected static ExecutorService ex;
    protected static final long serialVersionUID = 22554L;

    public BOSS() {}

    @Override
    public TechnicalInformation getTechnicalInformation() {
        TechnicalInformation 	result;
        result = new TechnicalInformation(TechnicalInformation.Type.ARTICLE);
        result.setValue(TechnicalInformation.Field.AUTHOR, "P. Schafer");
        result.setValue(TechnicalInformation.Field.TITLE, "The BOSS is concerned with time series classification in the presence of noise");
        result.setValue(TechnicalInformation.Field.JOURNAL, "Data Mining and Knowledge Discovery");
        result.setValue(TechnicalInformation.Field.VOLUME, "29");
        result.setValue(TechnicalInformation.Field.NUMBER,"6");
        result.setValue(TechnicalInformation.Field.PAGES, "1505-1530");
        result.setValue(TechnicalInformation.Field.YEAR, "2015");
        return result;
    }

    @Override
    public Capabilities getCapabilities(){
        Capabilities result = super.getCapabilities();
        result.disableAll();

        result.setMinimumNumberInstances(2);

        // attributes
        result.enable(Capabilities.Capability.RELATIONAL_ATTRIBUTES);
        result.enable(Capabilities.Capability.NUMERIC_ATTRIBUTES);

        // class
        result.enable(Capabilities.Capability.NOMINAL_CLASS);

        return result;
    }

    @Override
    public String getParameters() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.getParameters());

        sb.append(",numSeries,").append(numSeries);

        for (int n = 0; n < numSeries; n++) {
            sb.append(",numclassifiers,").append(n).append(",").append(numClassifiers[n]);

            for (int i = 0; i < numClassifiers[n]; ++i) {
                BOSSIndividual boss = classifiers[n].get(i);
                sb.append(",windowSize,").append(boss.getWindowSize()).append(",wordLength,").append(boss.getWordLength());
                sb.append(",alphabetSize,").append(boss.getAlphabetSize()).append(",norm,").append(boss.isNorm());
            }
        }

        return sb.toString();
    }

    public void useBestSettingsRBOSS(){
        ensembleSize = 250;
        maxEnsembleSize = 50;
        randomCVAccEnsemble = true;
        useCAWPE = true;
        reduceTrainInstances = true;
        trainProportion = 0.7;
    }

    //pass in an enum of hour, minute, day, and the amount of them.
    @Override
    public void setTrainTimeLimit(TimeUnit time, long amount){
        switch (time){
            case DAYS:
                contractTime = (long)(8.64e+13)*amount;
                break;
            case HOURS:
                contractTime = (long)(3.6e+12)*amount;
                break;
            case MINUTES:
                contractTime = (long)(6e+10)*amount;
                break;
            case SECONDS:
                contractTime = (long)(1e+9)*amount;
                break;
            case NANOSECONDS:
                contractTime = amount;
                break;
            default:
                throw new InvalidParameterException("Invalid time unit");
        }
        trainTimeContract = true;
    }

    @Override
    public void setMemoryLimit(DataUnit unit, long amount){
        switch (unit){
            case GIGABYTE:
                memoryLimit = amount*1073741824;
                break;
            case MEGABYTE:
                memoryLimit = amount*1048576;
                break;
            case BYTES:
                memoryLimit = amount;
                break;
            default:
                throw new InvalidParameterException("Invalid data unit");
        }
        memoryContract = true;
    }

    //Set the path where checkpointed versions will be stored
    @Override
    public void setSavePath(String path){
        checkpointPath = path;
        checkpoint = true;
    }

    //Define how to copy from a loaded object to this object
    @Override
    public void copyFromSerObject(Object obj) throws Exception{
        if(!(obj instanceof BOSS))
            throw new Exception("The SER file is not an instance of BOSS");
        BOSS saved = ((BOSS)obj);
        System.out.println("Loading BOSS.ser");

        //copy over variables from serialised object
        paramAccuracy = saved.paramAccuracy;
        paramTime = saved.paramTime;
        paramMemory = saved.paramMemory;
        ensembleSize = saved.ensembleSize;
        seed = saved.seed;
        ensembleSizePerChannel = saved.ensembleSizePerChannel;
        rand = saved.rand;
        randomEnsembleSelection = saved.randomEnsembleSelection;
        randomCVAccEnsemble = saved.randomCVAccEnsemble;
        useCAWPE = saved.useCAWPE;
        useFastTrainEstimate = saved.useFastTrainEstimate;
        maxEvalPerClass = saved.maxEvalPerClass;
        maxEval = saved.maxEval;
        maxWinLenProportion = saved.maxWinLenProportion;
        maxWinSearchProportion = saved.maxWinSearchProportion;
        reduceTrainInstances = saved.reduceTrainInstances;
        trainProportion = saved.trainProportion;
        maxTrainInstances = saved.maxTrainInstances;
        stratifiedSubsample = saved.stratifiedSubsample;
        cutoff = saved.cutoff;
//        loadAndFinish = saved.loadAndFinish;
        numSeries = saved.numSeries;
        numClassifiers = saved.numClassifiers;
        currentSeries = saved.currentSeries;
        isMultivariate = saved.isMultivariate;
//        wordLengths = saved.wordLengths;
//        alphabetSize = saved.alphabetSize;
//        correctThreshold = saved.correctThreshold;
        maxEnsembleSize = saved.maxEnsembleSize;
        bayesianParameterSelection = saved.bayesianParameterSelection;
        initialRandomParameters = saved.initialRandomParameters;
        initialParameterCount = saved.initialParameterCount;
        parameterPool = saved.parameterPool;
        prevParameters = saved.prevParameters;
//        checkpointPath = saved.checkpointPath;
//        serPath = saved.serPath;
//        checkpoint = saved.checkpoint;
        checkpointTime = saved.checkpointTime;
//        checkpointTimeDiff = checkpointTimeDiff;
        cleanupCheckpointFiles = saved.cleanupCheckpointFiles;
        contractTime = saved.contractTime;
        trainTimeContract = saved.trainTimeContract;
        underContractTime = saved.underContractTime;
        memoryLimit = saved.memoryLimit;
        bytesUsed = saved.bytesUsed;
        memoryContract = saved.memoryContract;
        underMemoryLimit = saved.underMemoryLimit;
        classifiersBuilt = saved.classifiersBuilt;
        lowestAccIdx = saved.lowestAccIdx;
        lowestAcc = saved.lowestAcc;
        trainCVPath = saved.trainCVPath;
        trainCV = saved.trainCV;
        trainResults = saved.trainResults;
        ensembleCvAcc = saved.ensembleCvAcc;
        ensembleCvPreds = saved.ensembleCvPreds;
        numThreads = saved.numThreads;
        multiThread = saved.multiThread;

        //load in each serisalised classifier
        classifiers = new LinkedList[numSeries];
        for (int n = 0; n < numSeries; n++) {
            classifiers[n] = new LinkedList();
            for (int i = 0; i < saved.numClassifiers[n]; i++) {
                System.out.println("Loading BOSSIndividual" + n + "-" + i + ".ser");

                FileInputStream fis = new FileInputStream(serPath + "BOSSIndividual" + n + "-" + i + ".ser");
                try (ObjectInputStream in = new ObjectInputStream(fis)) {
                    Object indv = in.readObject();

                    if (!(indv instanceof BOSSIndividual))
                        throw new Exception("The SER file " + n + "-" + i + " is not an instance of BOSSIndividual");
                    BOSSIndividual ser = ((BOSSIndividual) indv);
                    classifiers[n].add(ser);
                }
            }
        }

        checkpointTimeDiff = saved.checkpointTimeDiff + (System.nanoTime() - checkpointTime);
    }

    @Override
    public void writeCVTrainToFile(String outputPathAndName){
        trainCVPath = outputPathAndName;
        trainCV = true;
    }

    @Override
    public void setFindTrainAccuracyEstimate(boolean setCV){
        trainCV = setCV;
    }

    @Override
    public boolean findsTrainAccuracyEstimate(){ return trainCV; }

    @Override
    public ClassifierResults getTrainResults(){
        trainResults.setAcc(ensembleCvAcc);
        return trainResults;
    }

    public void setEnsembleSize(int size) {
        ensembleSize = size;
    }

    public void setMaxEnsembleSize(int size) {
        maxEnsembleSize = size;
    }

    public void setSeed(int i) {
        seed = i;
    }

    public void setRandomEnsembleSelection(boolean b){
        randomEnsembleSelection = b;
    }

    public void setRandomCVAccEnsemble(boolean b){
        randomCVAccEnsemble = b;
    }

    public void useCAWPE(boolean b) {
        useCAWPE = b;
    }

    public void setFastTrainEstimate(boolean b){
        useFastTrainEstimate = b;
    }

    public void setReduceTrainInstances(boolean b){
        reduceTrainInstances = b;
    }

    public void setTrainProportion(double d){
        trainProportion = d;
    }

    public void setCleanupCheckpointFiles(boolean b) {
        cleanupCheckpointFiles = b;
    }

    public void setCutoff(boolean b) {
        cutoff = b;
    }

    public void setMaxTrainInstances(int i){
        maxTrainInstances = i;
    }

    public void setMaxEval(int i) {
        maxEval = i;
    }

    public void setMaxEvalPerClass(int i) {
        maxEvalPerClass = i;
    }

    public void setMaxWinLenProportion(double d){
        maxWinLenProportion = d;
    }

    public void setMaxWinSearchProportion(double d){
        maxWinSearchProportion = d;
    }

    public void setBayesianParameterSelection(boolean b) { bayesianParameterSelection = b; }

    @Override
    public void buildClassifier(final Instances data) throws Exception {
        trainResults.setBuildTime(System.nanoTime());

        // can classifier handle the data?
        getCapabilities().testWithFail(data);

        if(data.checkForAttributeType(Attribute.RELATIONAL)){
            isMultivariate = true;
        }

        //path checkpoint files will be saved to
        serPath = checkpointPath + "/" + checkpointName(data.relationName()) + "/";
        File f = new File(serPath + "BOSS.ser");

        //Window length settings
        int seriesLength = isMultivariate ? channelLength(data)-1 : data.numAttributes()-1; //minus class attribute
        int minWindow = 10;
        int maxWindow = (int)(seriesLength*maxWinLenProportion);
        if (maxWindow < minWindow) minWindow = maxWindow/2;
        //whats the max number of window sizes that should be searched through
        double maxWindowSearches = seriesLength*maxWinSearchProportion;
        int winInc = (int)((maxWindow - minWindow) / maxWindowSearches);
        if (winInc < 1) winInc = 1;

        //if checkpointing and serialised files exist load said files
        if (checkpoint && f.exists()){
            long time = System.nanoTime();
            loadFromFile(serPath + "BOSS.ser");
            System.out.println("Spent " + (System.nanoTime() - time) + "nanoseconds loading files");
        }
        //initialise variables
        else {
            if (data.classIndex() != data.numAttributes()-1)
                throw new Exception("BOSS_BuildClassifier: Class attribute not set as last attribute in dataset");

            //Multivariate
            if (isMultivariate) {
                numSeries = numChannels(data);
                classifiers = new LinkedList[numSeries];

                for (int n = 0; n < numSeries; n++){
                    classifiers[n] = new LinkedList<>();
                }

                numClassifiers = new int[numSeries];

                if (ensembleSizePerChannel > 0){
                    ensembleSize = ensembleSizePerChannel*numSeries;
                }
            }
            //Univariate
            else{
                numSeries = 1;
                classifiers = new LinkedList[1];
                classifiers[0] = new LinkedList<>();
                numClassifiers = new int[1];
            }

            if (maxEvalPerClass > 0){
                maxEval = data.numClasses()*maxEvalPerClass;
            }

            rand = new Random(seed);

            if (randomCVAccEnsemble || randomEnsembleSelection) {
                parameterPool = uniqueParameters(minWindow, maxWindow, winInc);
            }
        }

        try{
            SizeOf.deepSizeOf("test");
        }
        catch (IllegalStateException e){
            if (memoryContract) {
                throw new Exception("Unable to contract memory with SizeOf unavailable, " +
                        "enable by linking to SizeOf.jar in VM options i.e. -javaagent:lib/SizeOf.jar");
            }
        }

        this.train = data;

        if (multiThread && numThreads == 1){
            numThreads = Runtime.getRuntime().availableProcessors();
        }

        //required to deal with multivariate datasets, each channel is split into its own instances
        Instances[] series;

        //Multivariate
        if (isMultivariate) {
            series = splitMultivariateInstances(data);
        }
        //Univariate
        else{
            series = new Instances[1];
            series[0] = data;
        }

        //Contracting
        if (trainTimeContract){
            ensembleSize = 0;
            underContractTime = true;
        }

        //If checkpointing and flag is set stop building.
        if (!(checkpoint && loadAndFinish)){
            //Randomly selected ensemble
            if (randomCVAccEnsemble){
                buildRandomCVAccBOSS(series);
            }
            //Randomly selected ensemble with accuracy filter
            else if (randomEnsembleSelection){
                buildRandomBOSS(series);
            }
            //Original BOSS/Accuracy cutoff ensemble
            else{
                buildBOSS(series, minWindow, maxWindow, winInc);
            }
        }

        //end train time in nanoseconds
        trainResults.setBuildTime(System.nanoTime() - trainResults.getBuildTime() - checkpointTimeDiff);

        //Estimate train accuracy
        if (trainCV) {
            trainResults.setTimeUnit(TimeUnit.NANOSECONDS);
            trainResults.setClassifierName(randomEnsembleSelection || randomCVAccEnsemble ? "RBOSS" : "BOSS");
            trainResults.setDatasetName(data.relationName());
            trainResults.setFoldID(seed);
            trainResults.setSplit("train");
            trainResults.setParas(getParameters());
            double result = findEnsembleTrainAcc(data);
            trainResults.finaliseResults();
            trainResults.writeFullResultsToFile(trainCVPath);

            System.out.println("CV acc ="+result);
        }

        //delete any serialised files and holding folder for checkpointing on completion
        if (checkpoint && cleanupCheckpointFiles){
            checkpointCleanup();
        }
    }

    private void buildRandomCVAccBOSS(Instances[] series) throws Exception {
        classifiersBuilt = new int[numSeries];
        lowestAccIdx = new int[numSeries];
        lowestAcc = new double[numSeries];
        for (int i = 0; i < numSeries; i++) lowestAcc[i] = Double.MAX_VALUE;

        //build classifiers up to a set size
        while (((underContractTime || sum(classifiersBuilt) < ensembleSize) && underMemoryLimit) && parameterPool[numSeries-1].size() > 0) {
            long indivBuildTime = System.nanoTime();
            boolean checkpointChange = false;
            double[] parameters = selectParameters();
            if (parameters == null) continue;

            BOSSIndividual boss = new BOSSIndividual((int)parameters[0], (int)parameters[1], (int)parameters[2], parameters[3] == 0, true, multiThread, numThreads);
            Instances data = resampleData(series[currentSeries], boss);
            boss.cleanAfterBuild = true;
            boss.seed = seed;
            boss.buildClassifier(data);
            boss.accuracy = individualTrainAcc(boss, data, numClassifiers[currentSeries] < maxEnsembleSize ? Double.MIN_VALUE : lowestAcc[currentSeries]);

            if (useCAWPE){
                boss.weight = Math.pow(boss.accuracy, 4);
                if (boss.weight == 0) boss.weight = 1;
            }

            if (bayesianParameterSelection) paramAccuracy[currentSeries].add(boss.accuracy);
            if (trainTimeContract) paramTime[currentSeries].add((double)(System.nanoTime() - indivBuildTime));
            if (memoryContract) paramMemory[currentSeries].add((double)SizeOf.deepSizeOf(boss));

            if (numClassifiers[currentSeries] < maxEnsembleSize){
                if (boss.accuracy < lowestAcc[currentSeries]){
                    lowestAccIdx[currentSeries] = classifiersBuilt[currentSeries];
                    lowestAcc[currentSeries] = boss.accuracy;
                }
                classifiers[currentSeries].add(boss);
                numClassifiers[currentSeries]++;
            }
            else if (boss.accuracy > lowestAcc[currentSeries]) {
                double[] newLowestAcc = findMinEnsembleAcc();
                lowestAccIdx[currentSeries] = (int)newLowestAcc[0];
                lowestAcc[currentSeries] = newLowestAcc[1];

                classifiers[currentSeries].remove(lowestAccIdx[currentSeries]);
                classifiers[currentSeries].add(lowestAccIdx[currentSeries], boss);
                checkpointChange = true;
            }

            classifiersBuilt[currentSeries]++;

            int prev = currentSeries;
            if (isMultivariate) {
                nextSeries();
            }

            if (checkpoint) {
                if (numClassifiers[currentSeries] < maxEnsembleSize) {
                    checkpoint(prev, -1);
                }
                else if (checkpointChange){
                    checkpoint(prev, lowestAccIdx[prev]);
                }
            }

            checkContracts();
        }

        if (cutoff){
            for (int n = 0; n < numSeries; n++) {
                double maxAcc = 0;
                for (int i = 0; i < classifiers[n].size(); i++){
                    if (classifiers[n].get(i).accuracy > maxAcc){
                        maxAcc = classifiers[n].get(i).accuracy;
                    }
                }

                for (int i = 0; i < classifiers[n].size(); i++){
                    BOSSIndividual b = classifiers[n].get(i);
                    if (b.accuracy < maxAcc * correctThreshold) {
                        classifiers[currentSeries].remove(i);
                        numClassifiers[n]--;
                        i--;
                    }
                }
            }
        }
    }

    private void buildRandomBOSS(Instances[] series) throws Exception {
        //build classifiers up to a set size
        while ((((underContractTime && numClassifiers[numSeries-1] < maxEnsembleSize)
                || sum(numClassifiers) < ensembleSize) && underMemoryLimit) && parameterPool[numSeries-1].size() > 0) {
            long indivBuildTime = System.nanoTime();
            double[] parameters = selectParameters();
            if (parameters == null) continue;

            BOSSIndividual boss = new BOSSIndividual((int)parameters[0], (int)parameters[1], (int)parameters[2], parameters[3] == 0, true, multiThread, numThreads);
            Instances data = resampleData(series[currentSeries], boss);
            boss.cleanAfterBuild = true;
            boss.seed = seed;
            boss.buildClassifier(data);
            classifiers[currentSeries].add(boss);
            numClassifiers[currentSeries]++;

            if (useCAWPE){
                if (boss.accuracy == -1) boss.accuracy = individualTrainAcc(boss, data, Double.MIN_VALUE);
                boss.weight = Math.pow(boss.accuracy, 4);
                if (boss.weight == 0) boss.weight = 1;
            }

            if (bayesianParameterSelection) {
                if (boss.accuracy == -1) boss.accuracy = individualTrainAcc(boss, data, Double.MIN_VALUE);
                paramAccuracy[currentSeries].add(boss.accuracy);
            }
            if (trainTimeContract) paramTime[currentSeries].add((double)(System.nanoTime() - indivBuildTime));
            if (memoryContract) paramMemory[currentSeries].add((double)SizeOf.deepSizeOf(boss));

            int prev = currentSeries;
            if (isMultivariate){
                nextSeries();
            }

            if (checkpoint) {
                checkpoint(prev, -1);
            }

            checkContracts();
        }
    }

    private void buildBOSS(Instances[] series, int minWindow, int maxWindow, int winInc) throws Exception {
        for (int n = 0; n < numSeries; n++) {
            currentSeries = n;
            double maxAcc = -1.0;

            //the acc of the worst member to make it into the final ensemble as it stands
            double minMaxAcc = -1.0;

            boolean[] normOptions = {true, false};

            for (boolean normalise : normOptions) {
                for (int winSize = minWindow; winSize <= maxWindow; winSize += winInc) {
                    BOSSIndividual boss = new BOSSIndividual(wordLengths[0], alphabetSize[0], winSize, normalise, true, multiThread, numThreads);
                    boss.seed = seed;
                    boss.buildClassifier(series[n]); //initial setup for this windowsize, with max word length

                    BOSSIndividual bestClassifierForWinSize = null;
                    double bestAccForWinSize = -1.0;

                    //find best word length for this window size
                    for (Integer wordLen : wordLengths) {
                        boss = boss.buildShortenedBags(wordLen); //in first iteration, same lengths (wordLengths[0]), will do nothing

                        double acc = individualTrainAcc(boss, series[n], bestAccForWinSize);

                        if (acc >= bestAccForWinSize) {
                            bestAccForWinSize = acc;
                            bestClassifierForWinSize = boss;
                        }
                    }

                    //if this window size's accuracy is not good enough to make it into the ensemble, dont bother storing at all
                    if (makesItIntoEnsemble(bestAccForWinSize, maxAcc, minMaxAcc, classifiers[n].size())) {
                        bestClassifierForWinSize.clean();
                        bestClassifierForWinSize.accuracy = bestAccForWinSize;
                        classifiers[n].add(bestClassifierForWinSize);

                        if (bestAccForWinSize > maxAcc) {
                            maxAcc = bestAccForWinSize;
                            //get rid of any extras that dont fall within the new max threshold
                            Iterator<BOSSIndividual> it = classifiers[n].iterator();
                            while (it.hasNext()) {
                                BOSSIndividual b = it.next();
                                if (b.accuracy < maxAcc * correctThreshold) {
                                    it.remove();
                                }
                            }
                        }

                        while (classifiers[n].size() > maxEnsembleSize) {
                            //cull the 'worst of the best' until back under the max size
                            int minAccInd = (int) findMinEnsembleAcc()[0];

                            classifiers[n].remove(minAccInd);
                        }

                        minMaxAcc = findMinEnsembleAcc()[1]; //new 'worst of the best' acc
                    }

                    numClassifiers[n] = classifiers[n].size();
                }
            }
        }
    }

    private void checkpoint(int seriesNo, int classifierNo){
        if(checkpointPath!=null){
            try{
                File f = new File(serPath);
                if(!f.isDirectory())
                    f.mkdirs();
                //time the checkpoint occured
                checkpointTime = System.nanoTime();

                if (seriesNo >= 0) {
                    if (classifierNo < 0) classifierNo = classifiers[seriesNo].size() - 1;

                    //save the last build individual classifier
                    BOSSIndividual indiv = classifiers[seriesNo].get(classifierNo);

                    FileOutputStream fos = new FileOutputStream(serPath + "BOSSIndividual" + seriesNo + "-" + classifierNo + ".ser");
                    try (ObjectOutputStream out = new ObjectOutputStream(fos)) {
                        out.writeObject(indiv);
                        out.close();
                        fos.close();
                    }
                }

                //dont take into account time spent serialising into build time
                checkpointTimeDiff += System.nanoTime() - checkpointTime;
                checkpointTime = System.nanoTime();

                //save this, classifiers and train data not included
                saveToFile(serPath + "RandomBOSStemp.ser");

                File file = new File(serPath + "RandomBOSStemp.ser");
                File file2 = new File(serPath + "BOSS.ser");
                file2.delete();
                file.renameTo(file2);

                checkpointTimeDiff += System.nanoTime() - checkpointTime;
            }
            catch(Exception e){
                e.printStackTrace();
                System.out.println("Serialisation to "+serPath+" FAILED");
            }
        }
    }

    private void checkpointCleanup(){
        File f = new File(serPath);
        String[] files = f.list();

        for (String file: files){
            File f2 = new File(f.getPath() + "\\" + file);
            boolean b = f2.delete();
        }

        f.delete();
    }

    private String checkpointName(String datasetName){
        String name = datasetName + seed + "BOSS";

        if (trainTimeContract){
            name += ("TTC" + contractTime);
        }
        else if (isMultivariate && ensembleSizePerChannel > 0){
            name += ("PC" + (ensembleSizePerChannel*numSeries));
        }
        else{
            name += ("S" + ensembleSize);
        }

        if (memoryContract){
            name += ("MC" + memoryLimit);
        }

        if (randomCVAccEnsemble) {
            name += ("M" + maxEnsembleSize);
        }

        if (useCAWPE){
            name += "W";
        }

        return name;
    }

    public void checkContracts(){
        underContractTime = System.nanoTime() - trainResults.getBuildTime() - checkpointTimeDiff < contractTime;
        underMemoryLimit = !memoryContract || bytesUsed < memoryLimit;
    }

    //[0] = index, [1] = acc
    private double[] findMinEnsembleAcc() {
        double minAcc = Double.MAX_VALUE;
        int minAccInd = 0;
        for (int i = 0; i < classifiers[currentSeries].size(); ++i) {
            double curacc = classifiers[currentSeries].get(i).accuracy;
            if (curacc < minAcc) {
                minAcc = curacc;
                minAccInd = i;
            }
        }

        return new double[] { minAccInd, minAcc };
    }

    private Instances[] uniqueParameters(int minWindow, int maxWindow, int winInc){
        Instances[] parameterPool = new Instances[numSeries];
        ArrayList<double[]> possibleParameters = new ArrayList();

        for (int normalise = 0; normalise < 2; normalise++) {
            for (Integer alphSize : alphabetSize) {
                for (int winSize = minWindow; winSize <= maxWindow; winSize += winInc) {
                    for (Integer wordLen : wordLengths) {
                        double[] parameters = {wordLen, alphSize, winSize, normalise};
                        possibleParameters.add(parameters);
                    }
                }
            }
        }

        int numAtts = possibleParameters.get(0).length+1;
        ArrayList<Attribute> atts = new ArrayList<>(numAtts);
        for (int i = 0; i < numAtts; i++){
            atts.add(new Attribute("att" + i));
        }

        prevParameters = new Instances[numSeries];
        initialParameterCount = new int[numSeries];

        for (int n = 0; n < numSeries; n++) {
            parameterPool[n] = new Instances("params", atts, possibleParameters.size());
            parameterPool[n].setClassIndex(numAtts-1);
            prevParameters[n] = new Instances(parameterPool[n], 0);
            prevParameters[n].setClassIndex(numAtts-1);

            for (int i = 0; i < possibleParameters.size(); i++) {
                DenseInstance inst = new DenseInstance(1, possibleParameters.get(i));
                inst.insertAttributeAt(numAtts-1);
                parameterPool[n].add(inst);
            }
        }

        if (bayesianParameterSelection){
            paramAccuracy = new ArrayList[numSeries];
            for (int i = 0; i < numSeries; i++){
                paramAccuracy[i] = new ArrayList<>();
            }
        }
        if (trainTimeContract){
            paramTime = new ArrayList[numSeries];
            for (int i = 0; i < numSeries; i++){
                paramTime[i] = new ArrayList<>();
            }
        }
        if (memoryContract){
            paramMemory = new ArrayList[numSeries];
            for (int i = 0; i < numSeries; i++){
                paramMemory[i] = new ArrayList<>();
            }
        }

        return parameterPool;
    }

    private double[] selectParameters() throws Exception {
        Instance params;

        if (trainTimeContract) {
            if (prevParameters[currentSeries].size() > 0) {
                for (int i = 0; i < paramTime[currentSeries].size(); i++) {
                    prevParameters[currentSeries].get(i).setClassValue(paramTime[currentSeries].get(i));
                }

                GaussianProcesses gp = new GaussianProcesses();
                gp.buildClassifier(prevParameters[currentSeries]);
                long remainingTime = contractTime - (System.nanoTime() - trainResults.getBuildTime() - checkpointTimeDiff);

                for (int i = 0; i < parameterPool[currentSeries].size(); i++) {
                    double pred = gp.classifyInstance(parameterPool[currentSeries].get(i));
                    if (pred > remainingTime) {
                        parameterPool[currentSeries].remove(i);
                        i--;
                    }
                }
            }
        }

        if (memoryContract) {
            if (prevParameters[currentSeries].size() > 0) {
                for (int i = 0; i < paramMemory[currentSeries].size(); i++) {
                    prevParameters[currentSeries].get(i).setClassValue(paramMemory[currentSeries].get(i));
                }

                GaussianProcesses gp = new GaussianProcesses();
                gp.buildClassifier(prevParameters[currentSeries]);
                long remainingMemory = memoryLimit - bytesUsed;

                for (int i = 0; i < parameterPool[currentSeries].size(); i++) {
                    double pred = gp.classifyInstance(parameterPool[currentSeries].get(i));
                    if (pred > remainingMemory) {
                        parameterPool[currentSeries].remove(i);
                        i--;
                    }
                }
            }
        }

        if (parameterPool[currentSeries].size() == 0){
            return null;
        }

        if (bayesianParameterSelection) {
            if (initialParameterCount[currentSeries] < initialRandomParameters) {
                initialParameterCount[currentSeries]++;
                params = parameterPool[currentSeries].remove(rand.nextInt(parameterPool[currentSeries].size()));
            } else {
                for (int i = 0; i < paramAccuracy[currentSeries].size(); i++){
                    prevParameters[currentSeries].get(i).setClassValue(paramAccuracy[currentSeries].get(i));
                }

                GaussianProcesses gp = new GaussianProcesses();
                gp.buildClassifier(prevParameters[currentSeries]);
                int bestIndex = 0;
                double bestAcc = -1;

                for (int i = 0; i < parameterPool[currentSeries].numInstances(); i++) {
                    double pred = gp.classifyInstance(parameterPool[currentSeries].get(i));

                    if (pred > bestAcc){
                        bestIndex = i;
                        bestAcc = pred;
                    }
                }

                params = parameterPool[currentSeries].remove(bestIndex);
            }
        }
        else {
            params = parameterPool[currentSeries].remove(rand.nextInt(parameterPool[currentSeries].size()));
        }

        prevParameters[currentSeries].add(params);
        return params.toDoubleArray();
    }

    private Instances resampleData(Instances series, BOSSIndividual boss){
        Instances data;
        int newSize;

        if (trainProportion > 0){
            newSize = (int)(series.numInstances()*trainProportion);
        }
        else{
            newSize = maxTrainInstances;
        }

        if (reduceTrainInstances && series.numInstances() > newSize){
            Sampler sampler;

            if (stratifiedSubsample){
                sampler = new RandomStratifiedIndexSampler(rand);
            }
            else{
                sampler = new RandomIndexSampler(rand);
            }

            sampler.setInstances(series);
            data = new Instances(series, newSize);
            boss.subsampleIndices = new ArrayList<>(newSize);

            for (int i = 0; i < newSize; i++){
                int n = (Integer)sampler.next();
                data.add(series.get(n));
                boss.subsampleIndices.add(n);
            }
        }
        else{
            data = series;
        }

        return data;
    }

    private double individualTrainAcc(BOSSIndividual boss, Instances series, double lowestAcc) throws Exception {
        int[] indicies;

        if (useFastTrainEstimate && maxEval < series.numInstances()){
            RandomRoundRobinIndexSampler sampler = new RandomRoundRobinIndexSampler(rand);
            sampler.setInstances(series);
            indicies = new int[maxEval];

            for (int i = 0; i < maxEval; ++i) {
                int subsampleIndex = sampler.next();
                indicies[i] = subsampleIndex;
            }
        }
        else {
            indicies = new int[series.numInstances()];

            for (int i = 0; i < series.numInstances(); ++i) {
                indicies[i] = i;
            }
        }

        int correct = 0;
        int numInst = indicies.length;
        int requiredCorrect = (int)(lowestAcc*numInst);

        if (multiThread){
            ex = Executors.newFixedThreadPool(numThreads);
            ArrayList<BOSSIndividual.TrainNearestNeighbourThread> threads = new ArrayList<>(sum(numClassifiers));

            for (int i = 0; i < numInst; ++i) {
                BOSSIndividual.TrainNearestNeighbourThread t = boss.new TrainNearestNeighbourThread(indicies[i]);
                threads.add(t);
                ex.execute(t);
            }

            ex.shutdown();
            while (!ex.isTerminated());

            for (BOSSIndividual.TrainNearestNeighbourThread t: threads){
                if (t.nn == series.get(t.testIndex).classValue()) {
                    ++correct;
                }
            }
        }
        else {
            for (int i = 0; i < numInst; ++i) {
                if (correct + numInst - i < requiredCorrect) {
                    return -1;
                }

                double c = boss.classifyInstance(indicies[i]); //classify series i, while ignoring its corresponding histogram i
                if (c == series.get(indicies[i]).classValue()) {
                    ++correct;
                }
            }
        }

        return (double) correct / (double) numInst;
    }

    private boolean makesItIntoEnsemble(double acc, double maxAcc, double minMaxAcc, int curEnsembleSize) {
        if (acc >= maxAcc * correctThreshold) {
            if (curEnsembleSize >= maxEnsembleSize)
                return acc > minMaxAcc;
            else
                return true;
        }

        return false;
    }

    public void nextSeries(){
        if (currentSeries == numSeries-1){
            currentSeries = 0;
        }
        else{
            currentSeries++;
        }
    }

    private double findEnsembleTrainAcc(Instances data) throws Exception {
        this.ensembleCvPreds = new double[data.numInstances()];

        double correct = 0;
        for (int i = 0; i < data.numInstances(); ++i) {
            long predTime = System.nanoTime();
            double[] probs = distributionForInstance(i, data.numClasses());
            predTime = System.nanoTime() - predTime;

            double c = 0;
            for (int j = 1; j < probs.length; j++)
                if (probs[j] > probs[(int) c])
                    c = j;

            //No need to do it againclassifyInstance(i, data.numClasses()); //classify series i, while ignoring its corresponding histogram i
            if (c == data.get(i).classValue())
                ++correct;

            this.ensembleCvPreds[i] = c;

            trainResults.addPrediction(data.get(i).classValue(), probs, c, predTime, "");
        }

        double result = correct / data.numInstances();

        return result;
    }

    public double getEnsembleCvAcc(){
        if(ensembleCvAcc>=0){
            return this.ensembleCvAcc;
        }

        try{
            return this.findEnsembleTrainAcc(train);
        }catch(Exception e){
            e.printStackTrace();
        }
        return -1;
    }

    public double[] getEnsembleCvPreds(){
        if(this.ensembleCvPreds==null){
            try{
                this.findEnsembleTrainAcc(train);
            }catch(Exception e){
                e.printStackTrace();
            }
        }

        return this.ensembleCvPreds;
    }

    //potentially scuffed when train set is subsampled, will have to revisit and discuss if this is a viable option
    //for estimation anyway.
    private double[] distributionForInstance(int test, int numclasses) throws Exception {
        double[][] classHist = new double[numSeries][numclasses];

        //get sum of all channels, votes from each are weighted the same.
        double sum[] = new double[numSeries];

        for (int n = 0; n < numSeries; n++) {
            for (BOSSIndividual classifier : classifiers[n]) {
                double classification;

                if (classifier.subsampleIndices == null){
                    classification = classifier.classifyInstance(test);
                }
                else if (classifier.subsampleIndices.contains(test)){
                    classification = classifier.classifyInstance(classifier.subsampleIndices.indexOf(test));
                }
                else{
                    classification = classifier.classifyInstance(train.get(test));
                }

                classHist[n][(int) classification] += classifier.weight;
                sum[n] += classifier.weight;
            }
        }

        double[] distributions = new double[numclasses];

        for (int n = 0; n < numSeries; n++){
            if (sum[n] != 0)
                for (int i = 0; i < classHist[n].length; ++i)
                    distributions[i] += (classHist[n][i] / sum[n]) / numSeries;
        }

        return distributions;
    }

    @Override
    public double classifyInstance(Instance instance) throws Exception {
        double[] dist = distributionForInstance(instance);

        double maxFreq=dist[0], maxClass=0;
        for (int i = 1; i < dist.length; ++i) {
            if (dist[i] > maxFreq) {
                maxFreq = dist[i];
                maxClass = i;
            }
            else if (dist[i] == maxFreq){
                if (rand.nextBoolean()){
                    maxClass = i;
                }
            }
        }

        return maxClass;
    }

    @Override
    public double[] distributionForInstance(Instance instance) throws Exception {
        double[][] classHist = new double[numSeries][instance.numClasses()];

        //get sum of all channels, votes from each are weighted the same.
        double sum[] = new double[numSeries];

        Instance[] series;

        //Multivariate
        if (isMultivariate) {
            series = splitMultivariateInstanceWithClassVal(instance);
        }
        //Univariate
        else {
            series = new Instance[1];
            series[0] = instance;
        }

        if (multiThread){
            ex = Executors.newFixedThreadPool(numThreads);
            ArrayList<BOSSIndividual.TestNearestNeighbourThread> threads = new ArrayList<>(sum(numClassifiers));

            for (int n = 0; n < numSeries; n++) {
                for (BOSSIndividual classifier : classifiers[n]) {
                    BOSSIndividual.TestNearestNeighbourThread t = classifier.new TestNearestNeighbourThread(instance, classifier.weight, n);
                    threads.add(t);
                    ex.execute(t);
                }
            }

            ex.shutdown();
            while (!ex.isTerminated());

            for (BOSSIndividual.TestNearestNeighbourThread t: threads){
                classHist[t.series][(int)t.nn] += t.weight;
                sum[t.series] += t.weight;
            }
        }
        else {
            for (int n = 0; n < numSeries; n++) {
                for (BOSSIndividual classifier : classifiers[n]) {
                    double classification = classifier.classifyInstance(series[n]);
                    classHist[n][(int) classification] += classifier.weight;
                    sum[n] += classifier.weight;
                }
            }
        }

        double[] distributions = new double[instance.numClasses()];

        for (int n = 0; n < numSeries; n++){
            if (sum[n] != 0)
                for (int i = 0; i < classHist[n].length; ++i)
                    distributions[i] += (classHist[n][i] / sum[n]) / numSeries;
        }

        return distributions;
    }

    public static void main(String[] args) throws Exception{
        int fold = 0;

        //Minimum working example
        String dataset = "ItalyPowerDemand";
        Instances train = ClassifierTools.loadData("Z:\\Data\\TSCProblems2018\\"+dataset+"\\"+dataset+"_TRAIN.arff");
        Instances test = ClassifierTools.loadData("Z:\\Data\\TSCProblems2018\\"+dataset+"\\"+dataset+"_TEST.arff");
        Instances[] data = resampleTrainAndTestInstances(train, test, fold);
        train = data[0];
        test = data[1];

        String dataset2 = "ERing";
        Instances train2 = ClassifierTools.loadData("Z:\\Data\\MultivariateTSCProblems\\"+dataset2+"\\"+dataset2+"_TRAIN.arff");
        Instances test2 = ClassifierTools.loadData("Z:\\Data\\MultivariateTSCProblems\\"+dataset2+"\\"+dataset2+"_TEST.arff");
        Instances[] data2 = resampleMultivariateTrainAndTestInstances(train2, test2, fold);
        train2 = data2[0];
        test2 = data2[1];

        BOSS c;
        double accuracy;

        c = new BOSS();
        c.useBestSettingsRBOSS();
        c.setSeed(fold);
        c.buildClassifier(train);
        accuracy = ClassifierTools.accuracy(test, c);

        System.out.println("CVAcc CAWPE BOSS accuracy on " + dataset + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.useBestSettingsRBOSS();
        c.setSeed(fold);
        c.buildClassifier(train2);
        accuracy = ClassifierTools.accuracy(test2, c);

        System.out.println("CVAcc CAWPE BOSS accuracy on " + dataset2 + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.useBestSettingsRBOSS();
        c.setBayesianParameterSelection(true);
        c.setSeed(fold);
        c.buildClassifier(train);
        accuracy = ClassifierTools.accuracy(test, c);

        System.out.println("Bayesian CVAcc CAWPE BOSS accuracy on " + dataset + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.useBestSettingsRBOSS();
        c.setBayesianParameterSelection(true);
        c.setSeed(fold);
        c.buildClassifier(train2);
        accuracy = ClassifierTools.accuracy(test2, c);

        System.out.println("Bayesian CVAcc CAWPE BOSS accuracy on " + dataset2 + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.ensembleSize = 250;
        c.setMaxEnsembleSize(50);
        c.setRandomCVAccEnsemble(true);
        c.setSeed(fold);
        c.useFastTrainEstimate = true;
        c.reduceTrainInstances = true;
        c.setMaxEvalPerClass(50);
        c.setMaxTrainInstances(500);
        c.buildClassifier(train);
        accuracy = ClassifierTools.accuracy(test, c);

        System.out.println("FastMax CVAcc BOSS accuracy on " + dataset + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.ensembleSize = 250;
        c.setMaxEnsembleSize(50);
        c.setRandomCVAccEnsemble(true);
        c.setSeed(fold);
        c.useFastTrainEstimate = true;
        c.reduceTrainInstances = true;
        c.setMaxEvalPerClass(50);
        c.setMaxTrainInstances(500);
        c.buildClassifier(train2);
        accuracy = ClassifierTools.accuracy(test2, c);

        System.out.println("FastMax CVAcc BOSS accuracy on " + dataset2 + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.ensembleSize = 100;
        c.randomEnsembleSelection = true;
        c.useCAWPE(true);
        c.setSeed(fold);
        c.setReduceTrainInstances(true);
        c.setTrainProportion(0.7);
        c.buildClassifier(train);
        accuracy = ClassifierTools.accuracy(test, c);

        System.out.println("CAWPE Subsample BOSS accuracy on " + dataset + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.ensembleSize = 100;
        c.randomEnsembleSelection = true;
        c.useCAWPE(true);
        c.setSeed(fold);
        c.setReduceTrainInstances(true);
        c.setTrainProportion(0.7);
        c.buildClassifier(train2);
        accuracy = ClassifierTools.accuracy(test2, c);

        System.out.println("CAWPE Subsample BOSS accuracy on " + dataset2 + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.setRandomEnsembleSelection(true);
        c.setTrainTimeLimit(TimeUnit.MINUTES, 1);
        c.setCleanupCheckpointFiles(true);
        c.setSavePath("D:\\");
        c.setSeed(fold);
        c.buildClassifier(train);
        accuracy = ClassifierTools.accuracy(test, c);

        System.out.println("Contract 1 Min Checkpoint BOSS accuracy on " + dataset + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.setRandomEnsembleSelection(true);
        c.setTrainTimeLimit(TimeUnit.MINUTES, 1);
        c.setCleanupCheckpointFiles(true);
        c.setSavePath("D:\\");
        c.setSeed(fold);
        c.buildClassifier(train2);
        accuracy = ClassifierTools.accuracy(test2, c);

        System.out.println("Contract 1 Min Checkpoint BOSS accuracy on " + dataset2 + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.setRandomEnsembleSelection(true);
        c.setMemoryLimit(DataUnit.MEGABYTE, 500);
        c.setSeed(fold);
        c.buildClassifier(train);
        accuracy = ClassifierTools.accuracy(test, c);

        System.out.println("Contract 500MB BOSS accuracy on " + dataset + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.setRandomEnsembleSelection(true);
        c.setMemoryLimit(DataUnit.MEGABYTE, 500);
        c.setSeed(fold);
        c.buildClassifier(train2);
        accuracy = ClassifierTools.accuracy(test2, c);

        System.out.println("Contract 500MB BOSS accuracy on " + dataset2 + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.buildClassifier(train);
        accuracy = ClassifierTools.accuracy(test, c);

        System.out.println("BOSS accuracy on " + dataset + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        c = new BOSS();
        c.buildClassifier(train2);
        accuracy = ClassifierTools.accuracy(test2, c);

        System.out.println("BOSS accuracy on " + dataset2 + " fold " + fold + " = " + accuracy + " numClassifiers = " + Arrays.toString(c.numClassifiers));

        //Output 22/07/19
        /*
        CVAcc CAWPE BOSS accuracy on ItalyPowerDemand fold 0 = 0.923226433430515 numClassifiers = [50]
        CVAcc CAWPE BOSS accuracy on ERing fold 0 = 0.8851851851851852 numClassifiers = [50, 50, 50, 50]
        Bayesian CVAcc CAWPE BOSS accuracy on ItalyPowerDemand fold 0 = 0.9300291545189504 numClassifiers = [50]
        Bayesian CVAcc CAWPE BOSS accuracy on ERing fold 0 = 0.8851851851851852 numClassifiers = [50, 50, 50, 50]
        FastMax CVAcc BOSS accuracy on ItalyPowerDemand fold 0 = 0.8415937803692906 numClassifiers = [50]
        FastMax CVAcc BOSS accuracy on ERing fold 0 = 0.725925925925926 numClassifiers = [50, 50, 50, 50]
        CAWPE Subsample BOSS accuracy on ItalyPowerDemand fold 0 = 0.9271137026239067 numClassifiers = [80]
        CAWPE Subsample BOSS accuracy on ERing fold 0 = 0.8592592592592593 numClassifiers = [25, 25, 25, 25]
        Contract 1 Min Checkpoint BOSS accuracy on ItalyPowerDemand fold 0 = 0.6958211856171039 numClassifiers = [80]
        Contract 1 Min Checkpoint BOSS accuracy on ERing fold 0 = 0.5259259259259259 numClassifiers = [190, 190, 190, 190]
        Contract 500MB BOSS accuracy on ItalyPowerDemand fold 0 = 0.7103984450923226 numClassifiers = [50]
        Contract 500MB BOSS accuracy on ERing fold 0 = 0.4740740740740741 numClassifiers = [13, 13, 12, 12]
        BOSS accuracy on ItalyPowerDemand fold 0 = 0.9271137026239067 numClassifiers = [4]
        BOSS accuracy on ERing fold 0 = 0.7925925925925926 numClassifiers = [4, 1, 3, 6]
        */
    }

    /**
     * BOSS classifier to be used with known parameters, for boss with parameter search, use BOSSEnsemble.
     *
     * Current implementation of BitWord as of 07/11/2016 only supports alphabetsize of 4, which is the expected value
     * as defined in the paper
     *
     * Params: wordLength, alphabetSize, windowLength, normalise?
     *
     * @author James Large. Enhanced by original author Patrick Schaefer
     *
     * Implementation based on the algorithm described in getTechnicalInformation()
     */
    public static class BOSSIndividual extends AbstractClassifier implements Serializable, Comparable<BOSSIndividual> {

        //all sfa words found in original buildClassifier(), no numerosity reduction/shortening applied
        protected BitWord [/*instance*/][/*windowindex*/] SFAwords;

        //histograms of words of the current wordlength with numerosity reduction applied (if selected)
        protected ArrayList<Bag> bags;

        //breakpoints to be found by MCB
        protected double[/*letterindex*/][/*breakpointsforletter*/] breakpoints;

        protected int numClasses;

        protected double inverseSqrtWindowSize;
        protected int windowSize;
        protected int wordLength;
        protected int alphabetSize;
        protected boolean norm;
        protected boolean numerosityReduction = true;
        protected boolean cleanAfterBuild = false;

        protected double accuracy = -1;
        protected double weight = 1;
        protected ArrayList<Integer> subsampleIndices;

        protected boolean multiThread = false;
        protected int numThreads = 1;

        protected int seed = 0;
        protected Random rand;

        protected static final long serialVersionUID = 22551L;

        public BOSSIndividual(int wordLength, int alphabetSize, int windowSize, boolean normalise, boolean numerosityReduction, boolean multiThread, int numThreads) {
            this.wordLength = wordLength;
            this.alphabetSize = alphabetSize;
            this.windowSize = windowSize;
            this.inverseSqrtWindowSize = 1.0 / Math.sqrt(windowSize);
            this.norm = normalise;
            this.numerosityReduction = numerosityReduction;
            this.multiThread = multiThread;
            this.numThreads = numThreads;
        }

        public BOSSIndividual(int wordLength, int alphabetSize, int windowSize, boolean normalise) {
            this.wordLength = wordLength;
            this.alphabetSize = alphabetSize;
            this.windowSize = windowSize;
            this.inverseSqrtWindowSize = 1.0 / Math.sqrt(windowSize);
            this.norm = normalise;
        }

        /**
         * Used when shortening histograms, copies 'meta' data over, but with shorter
         * word length, actual shortening happens separately
         */
        public BOSSIndividual(BOSSIndividual boss, int wordLength) {
            this.wordLength = wordLength;

            this.windowSize = boss.windowSize;
            this.inverseSqrtWindowSize = boss.inverseSqrtWindowSize;
            this.alphabetSize = boss.alphabetSize;
            this.norm = boss.norm;
            this.numerosityReduction = boss.numerosityReduction;

            this.SFAwords = boss.SFAwords;
            this.breakpoints = boss.breakpoints;

            this.multiThread = boss.multiThread;
            this.numThreads = boss.numThreads;

            this.seed = boss.seed;
            this.rand = boss.rand;

            this.bags = new ArrayList<>(boss.bags.size());
            this.numClasses = boss.numClasses;
        }

        @Override
        public int compareTo(BOSSIndividual o) {
            return Double.compare(this.accuracy, o.accuracy);
        }

        public static class Bag extends HashMap<BitWord, Integer> {
            double classVal;
            protected static final long serialVersionUID = 22552L;

            public Bag() {
                super();
            }

            public Bag(int classValue) {
                super();
                classVal = classValue;
            }

            public double getClassVal() { return classVal; }
            public void setClassVal(double classVal) { this.classVal = classVal; }       
        }

        public int getWindowSize() { return windowSize; }
        public int getWordLength() { return wordLength; }
        public int getAlphabetSize() { return alphabetSize; }
        public boolean isNorm() { return norm; }

        /**
         * @return { numIntervals(word length), alphabetSize, slidingWindowSize, normalise? } 
         */
        public int[] getParameters() {
            return new int[] { wordLength, alphabetSize, windowSize };
        }

        public void setSeed(int i){ seed = i; }

        public void clean() {
            SFAwords = null;
        }

        protected double[][] performDFT(double[][] windows) {
            double[][] dfts = new double[windows.length][wordLength];
            for (int i = 0; i < windows.length; ++i) {
                dfts[i] = DFT(windows[i]);
            }
            return dfts;
        }

        protected double stdDev(double[] series) {
            double sum = 0.0;
            double squareSum = 0.0;
            for (int i = 0; i < windowSize; i++) {
                sum += series[i];
                squareSum += series[i]*series[i];
            }

            double mean = sum / series.length;
            double variance = squareSum / series.length - mean*mean;
            return variance > 0 ? Math.sqrt(variance) : 1.0;
        }

        protected double[] DFT(double[] series) {
            //taken from FFT.java but 
            //return just a double[] size n, { real1, imag1, ... realn/2, imagn/2 }
            //instead of Complex[] size n/2

            //only calculating first wordlength/2 coefficients (output values), 
            //and skipping first coefficient if the data is to be normalised
            int n=series.length;
            int outputLength = wordLength/2;
            int start = (norm ? 1 : 0);

            //normalize the disjoint windows and sliding windows by dividing them by their standard deviation 
            //all Fourier coefficients are divided by sqrt(windowSize)

            double normalisingFactor = inverseSqrtWindowSize / stdDev(series);

            double[] dft=new double[outputLength*2];

            for (int k = start; k < start + outputLength; k++) {  // For each output element
                float sumreal = 0;
                float sumimag = 0;
                for (int t = 0; t < n; t++) {  // For each input element
                    sumreal +=  series[t]*Math.cos(2*Math.PI * t * k / n);
                    sumimag += -series[t]*Math.sin(2*Math.PI * t * k / n);
                }
                dft[(k-start)*2]   = sumreal * normalisingFactor;
                dft[(k-start)*2+1] = sumimag * normalisingFactor;
            }
            return dft;
        }

        private double[] DFTunnormed(double[] series) {
            //taken from FFT.java but 
            //return just a double[] size n, { real1, imag1, ... realn/2, imagn/2 }
            //instead of Complex[] size n/2

            //only calculating first wordlength/2 coefficients (output values), 
            //and skipping first coefficient if the data is to be normalised
            int n=series.length;
            int outputLength = wordLength/2;
            int start = (norm ? 1 : 0);

            double[] dft = new double[outputLength*2];
            double twoPi = 2*Math.PI / n;

            for (int k = start; k < start + outputLength; k++) {  // For each output element
                float sumreal = 0;
                float sumimag = 0;
                for (int t = 0; t < n; t++) {  // For each input element
                    sumreal +=  series[t]*Math.cos(twoPi * t * k);
                    sumimag += -series[t]*Math.sin(twoPi * t * k);
                }
                dft[(k-start)*2]   = sumreal;
                dft[(k-start)*2+1] = sumimag;
            }
            return dft;
        }

        private double[] normalizeDFT(double[] dft, double std) {
            double normalisingFactor = (std > 0? 1.0 / std : 1.0) * inverseSqrtWindowSize;
            for (int i = 0; i < dft.length; i++)
                dft[i] *= normalisingFactor;

            return dft;
        }

        private double[][] performMFT(double[] series) {
            // ignore DC value?
            int startOffset = norm ? 2 : 0;
            int l = wordLength;
            l = l + l % 2; // make it even
            double[] phis = new double[l];
            for (int u = 0; u < phis.length; u += 2) {
                double uHalve = -(u + startOffset) / 2;
                phis[u] = realephi(uHalve, windowSize);
                phis[u + 1] = complexephi(uHalve, windowSize);
            }

            // means and stddev for each sliding window
            int end = Math.max(1, series.length - windowSize + 1);
            double[] means = new double[end];
            double[] stds = new double[end];
            calcIncrementalMeanStddev(windowSize, series, means, stds);
            // holds the DFT of each sliding window
            double[][] transformed = new double[end][];
            double[] mftData = null;

            for (int t = 0; t < end; t++) {
                // use the MFT
                if (t > 0) {
                    for (int k = 0; k < l; k += 2) {
                        double real1 = (mftData[k] + series[t + windowSize - 1] - series[t - 1]);
                        double imag1 = (mftData[k + 1]);
                        double real = complexMulReal(real1, imag1, phis[k], phis[k + 1]);
                        double imag = complexMulImag(real1, imag1, phis[k], phis[k + 1]);
                        mftData[k] = real;
                        mftData[k + 1] = imag;
                    }
                } // use the DFT for the first offset
                else {
                    mftData = Arrays.copyOf(series, windowSize);
                    mftData = DFTunnormed(mftData);
                }
                // normalization for lower bounding
                transformed[t] = normalizeDFT(Arrays.copyOf(mftData, l), stds[t]);
            }
            return transformed;
        }

        private void calcIncrementalMeanStddev(int windowLength, double[] series, double[] means, double[] stds) {
            double sum = 0;
            double squareSum = 0;
            // it is faster to multiply than to divide
            double rWindowLength = 1.0 / (double) windowLength;
            double[] tsData = series;
            for (int ww = 0; ww < windowLength; ww++) {
                sum += tsData[ww];
                squareSum += tsData[ww] * tsData[ww];
            }
            means[0] = sum * rWindowLength;
            double buf = squareSum * rWindowLength - means[0] * means[0];
            stds[0] = buf > 0 ? Math.sqrt(buf) : 0;
            for (int w = 1, end = tsData.length - windowLength + 1; w < end; w++) {
                sum += tsData[w + windowLength - 1] - tsData[w - 1];
                means[w] = sum * rWindowLength;
                squareSum += tsData[w + windowLength - 1] * tsData[w + windowLength - 1] - tsData[w - 1] * tsData[w - 1];
                buf = squareSum * rWindowLength - means[w] * means[w];
                stds[w] = buf > 0 ? Math.sqrt(buf) : 0;
            }
        }

        private static double complexMulReal(double r1, double im1, double r2, double im2) {
            return r1 * r2 - im1 * im2;
        }

        private static double complexMulImag(double r1, double im1, double r2, double im2) {
            return r1 * im2 + r2 * im1;
        }

        private static double realephi(double u, double M) {
            return Math.cos(2 * Math.PI * u / M);
        }

        private static double complexephi(double u, double M) {
            return -Math.sin(2 * Math.PI * u / M);
        }

        protected double[][] disjointWindows(double [] data) {
            int amount = (int)Math.ceil(data.length/(double)windowSize);
            double[][] subSequences = new double[amount][windowSize];

            for (int win = 0; win < amount; ++win) { 
                int offset = Math.min(win*windowSize, data.length-windowSize);

                //copy the elements windowStart to windowStart+windowSize from data into 
                //the subsequence matrix at position windowStart
                System.arraycopy(data,offset,subSequences[win],0,windowSize);
            }

            return subSequences;
        }

        protected double[][] MCB(Instances data) {
            double[][][] dfts = new double[data.numInstances()][][];

            int sample = 0;
            for (Instance inst : data)
                dfts[sample++] = performDFT(disjointWindows(toArrayNoClass(inst))); //approximation

            int numInsts = dfts.length;
            int numWindowsPerInst = dfts[0].length;
            int totalNumWindows = numInsts*numWindowsPerInst;

            breakpoints = new double[wordLength][alphabetSize]; 

            for (int letter = 0; letter < wordLength; ++letter) { //for each dft coeff

                //extract this column from all windows in all instances
                double[] column = new double[totalNumWindows];
                for (int inst = 0; inst < numInsts; ++inst)
                    for (int window = 0; window < numWindowsPerInst; ++window) {
                        //rounding dft coefficients to reduce noise
                        column[(inst * numWindowsPerInst) + window] = Math.round(dfts[inst][window][letter]*100.0)/100.0;   
                    }

                //sort, and run through to find breakpoints for equi-depth bins
                Arrays.sort(column);

                double binIndex = 0;
                double targetBinDepth = (double)totalNumWindows / (double)alphabetSize; 

                for (int bp = 0; bp < alphabetSize-1; ++bp) {
                    binIndex += targetBinDepth;
                    breakpoints[letter][bp] = column[(int)binIndex];
                }

                breakpoints[letter][alphabetSize-1] = Double.MAX_VALUE; //last one can always = infinity
            }

            return breakpoints;
        }

        /**
         * Builds a brand new boss bag from the passed fourier transformed data, rather than from
         * looking up existing transforms from earlier builds (i.e. SFAWords). 
         * 
         * to be used e.g to transform new test instances
         */
        protected Bag createBagSingle(double[][] dfts) {
            Bag bag = new Bag();
            BitWord lastWord = new BitWord();

            for (double[] d : dfts) {
                BitWord word = createWord(d);
                //add to bag, unless num reduction applies
                if (numerosityReduction && word.equals(lastWord))
                    continue;

                Integer val = bag.get(word);
                if (val == null)
                    val = 0;
                bag.put(word, ++val);   

                lastWord = word;
            }

            return bag;
        }

        protected BitWord createWord(double[] dft) {
            BitWord word = new BitWord(wordLength);
            for (int l = 0; l < wordLength; ++l) //for each letter
                for (int bp = 0; bp < alphabetSize; ++bp) //run through breakpoints until right one found
                    if (dft[l] <= breakpoints[l][bp]) {
                        word.push(bp); //add corresponding letter to word
                        break;
                    }

            return word;
        }

        /**
         * @return data of passed instance in a double array with the class value removed if present
         */
        protected static double[] toArrayNoClass(Instance inst) {
            int length = inst.numAttributes();
            if (inst.classIndex() >= 0)
                --length;

            double[] data = new double[length];

            for (int i=0, j=0; i < inst.numAttributes(); ++i)
                if (inst.classIndex() != i)
                    data[j++] = inst.value(i);

            return data;
        }

        /**
         * @return BOSSTransform-ed bag, built using current parameters
         */
        public Bag BOSSTransform(Instance inst) {
            double[][] mfts = performMFT(toArrayNoClass(inst)); //approximation     
            Bag bag = createBagSingle(mfts); //discretisation/bagging
            bag.setClassVal(inst.classValue());

            return bag;
        }

        /**
         * Shortens all bags in this BOSS instance (histograms) to the newWordLength, if wordlengths
         * are same, instance is UNCHANGED
         *
         * @param newWordLength wordLength to shorten it to
         * @return new boss classifier with newWordLength, or passed in classifier if wordlengths are same
         */
        public BOSSIndividual buildShortenedBags(int newWordLength) throws Exception {
            if (newWordLength == wordLength) //case of first iteration of word length search in ensemble
                return this;
            if (newWordLength > wordLength)
                throw new Exception("Cannot incrementally INCREASE word length, current:"+wordLength+", requested:"+newWordLength);
            if (newWordLength < 2)
                throw new Exception("Invalid wordlength requested, current:"+wordLength+", requested:"+newWordLength);

            BOSSIndividual newBoss = new BOSSIndividual(this, newWordLength);

            //build hists with new word length from SFA words, and copy over the class values of original insts
            for (int i = 0; i < bags.size(); ++i) {
                Bag newBag = createBagFromWords(newWordLength, SFAwords[i]);
                newBag.setClassVal(bags.get(i).getClassVal());
                newBoss.bags.add(newBag);
            }

            return newBoss;
        }

        /**
         * Builds a bag from the set of words for a pre-transformed series of a given wordlength.
         */
        protected Bag createBagFromWords(int thisWordLength, BitWord[] words) {
            Bag bag = new Bag();
            BitWord lastWord = new BitWord();

            for (BitWord w : words) {
                BitWord word = new BitWord(w);
                if (wordLength != thisWordLength)
                    word.shorten(BitWord.MAX_LENGTH-thisWordLength);

                //add to bag, unless num reduction applies
                if (numerosityReduction && word.equals(lastWord))
                    continue;

                Integer val = bag.get(word);
                if (val == null)
                    val = 0;
                bag.put(word, ++val);   

                lastWord = word;
            }

            return bag;
        }

        protected BitWord[] createSFAwords(Instance inst) {
            double[][] dfts = performMFT(toArrayNoClass(inst)); //approximation     
            BitWord[] words = new BitWord[dfts.length];
            for (int window = 0; window < dfts.length; ++window) 
                words[window] = createWord(dfts[window]);//discretisation

            return words;
        }

        @Override
        public void buildClassifier(Instances data) throws Exception {
            if (data.classIndex() != data.numAttributes()-1)
                throw new Exception("BOSS_BuildClassifier: Class attribute not set as last attribute in dataset");

            breakpoints = MCB(data); //breakpoints to be used for making sfa words for train AND test data
            SFAwords = new BitWord[data.numInstances()][];
            bags = new ArrayList<>(data.numInstances());
            rand = new Random(seed);
            numClasses = data.numClasses();

            if (multiThread){
                ex = Executors.newFixedThreadPool(numThreads);
                ArrayList<TransformThread> threads = new ArrayList<>(data.numInstances());

                for (int inst = 0; inst < data.numInstances(); ++inst) {
                    TransformThread t = new TransformThread(inst, data.get(inst));
                    threads.add(t);
                    bags.add(null);
                    ex.execute(t);
                }

                ex.shutdown();
                while (!ex.isTerminated());

                for (TransformThread t: threads){
                    bags.set(t.i, t.bag);
                }
            }
            else {
                for (int inst = 0; inst < data.numInstances(); ++inst) {
                    SFAwords[inst] = createSFAwords(data.get(inst));

                    Bag bag = createBagFromWords(wordLength, SFAwords[inst]);
                    bag.setClassVal(data.get(inst).classValue());
                    bags.add(bag);
                }
            }

            if (cleanAfterBuild) {
                clean();
            }
        }

        /**
         * Computes BOSS distance between two bags d(test, train), is NON-SYMETRIC operation, ie d(a,b) != d(b,a).
         * 
         * Quits early if the dist-so-far is greater than bestDist (assumed dist is still the squared distance), and returns Double.MAX_VALUE
         * 
         * @return distance FROM instA TO instB, or Double.MAX_VALUE if it would be greater than bestDist
         */
        public double BOSSdistance(Bag instA, Bag instB, double bestDist) {
            double dist = 0.0;

            //find dist only from values in instA
            for (Entry<BitWord, Integer> entry : instA.entrySet()) {
                Integer valA = entry.getValue();
                Integer valB = instB.get(entry.getKey());
                if (valB == null)
                    valB = 0;
                dist += (valA-valB)*(valA-valB);

                if (dist > bestDist)
                    return Double.MAX_VALUE;
            }

            return dist;
        }

        @Override
        public double classifyInstance(Instance instance) throws Exception{
            BOSSIndividual.Bag testBag = BOSSTransform(instance);

            //1NN BOSS distance
            double bestDist = Double.MAX_VALUE;
            double nn = -1;

            for (int i = 0; i < bags.size(); ++i) {
                double dist = BOSSdistance(testBag, bags.get(i), bestDist);

                if (dist < bestDist) {
                    bestDist = dist;
                    nn = bags.get(i).getClassVal();
                }
            }

            return nn;
        }

        /**
         * Used within BOSSEnsemble as part of a leave-one-out crossvalidation, to skip having to rebuild
         * the classifier every time (since the n histograms would be identical each time anyway), therefore this classifies
         * the instance at the index passed while ignoring its own corresponding histogram
         *
         * @param testIndex index of instance to classify
         * @return classification
         */
        public double classifyInstance(int testIndex) throws Exception{
            BOSSIndividual.Bag testBag = bags.get(testIndex);

            //1NN BOSS distance
            double bestDist = Double.MAX_VALUE;
            double nn = 0;

            for (int i = 0; i < bags.size(); ++i) {
                if (i == testIndex) //skip 'this' one, leave-one-out
                    continue;

                double dist = BOSSdistance(testBag, bags.get(i), bestDist);

                if (dist < bestDist) {
                    bestDist = dist;
                    nn = bags.get(i).getClassVal();
                }
            }

            return nn;
        }

        public class TestNearestNeighbourThread implements Runnable{
            Instance inst;
            double weight;
            int series;
            double nn = -1.0;

            public TestNearestNeighbourThread(Instance inst, double weight, int series){
                this.inst = inst;
                this.series = series;
                this.weight = weight;
            }

            @Override
            public void run() {
                BOSSIndividual.Bag testBag = BOSSTransform(inst);

                //1NN BOSS distance
                double bestDist = Double.MAX_VALUE;

                for (int i = 0; i < bags.size(); ++i) {
                    double dist = BOSSdistance(testBag, bags.get(i), bestDist);

                    if (dist < bestDist) {
                        bestDist = dist;
                        nn = bags.get(i).getClassVal();
                    }
                }
            }
        }

        public class TrainNearestNeighbourThread implements Runnable{
            int testIndex;
            double nn = -1.0;

            public TrainNearestNeighbourThread(int testIndex){
                this.testIndex = testIndex;
            }

            @Override
            public void run() {
                BOSSIndividual.Bag testBag = bags.get(testIndex);

                //1NN BOSS distance
                double bestDist = Double.MAX_VALUE;

                for (int i = 0; i < bags.size(); ++i) {
                    if (i == testIndex) //skip 'this' one, leave-one-out
                        continue;

                    double dist = BOSSdistance(testBag, bags.get(i), bestDist);

                    if (dist < bestDist) {
                        bestDist = dist;
                        nn = bags.get(i).getClassVal();
                    }
                }
            }
        }

        private class TransformThread implements Runnable{
            int i;
            Instance inst;
            BOSSIndividual.Bag bag;

            public TransformThread(int i, Instance inst){
                this.i = i;
                this.inst = inst;
            }

            @Override
            public void run() {
                SFAwords[i] = createSFAwords(inst);

                bag = createBagFromWords(wordLength, SFAwords[i]);
                bag.setClassVal(inst.classValue());
            }
        }
    }
}