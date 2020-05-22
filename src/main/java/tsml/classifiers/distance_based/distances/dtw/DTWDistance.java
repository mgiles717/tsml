package tsml.classifiers.distance_based.distances.dtw;


import tsml.classifiers.distance_based.distances.BaseDistanceMeasure;
import tsml.classifiers.distance_based.utils.params.ParamHandler;
import tsml.classifiers.distance_based.utils.params.ParamSet;
import weka.core.Instance;
import weka.core.neighboursearch.PerformanceStats;

/**
 * DTW distance measure.
 * <p>
 * Contributors: goastler
 */
public class DTWDistance extends BaseDistanceMeasure implements DTW {

    // the distance matrix produced by the distance function
    private double[][] distanceMatrix;
    // whether to keep the distance matrix
    private boolean keepDistanceMatrix = false;
    private int warpingWindow = -1;
    private double warpingWindowPercentage = -1;
    private boolean warpingWindowInPercentage = false;

    public DTWDistance() {
    }

    public DTWDistance(int warpingWindow) {
        this();
        setWarpingWindow(warpingWindow);
    }

    public int getWarpingWindow() {
        return warpingWindow;
    }

    @Override
    public void setWarpingWindowPercentage(final double percentage) {
        this.warpingWindowPercentage = percentage;
        warpingWindowInPercentage = true;
    }

    @Override
    public double getWarpingWindowPercentage() {
        return warpingWindowPercentage;
    }

    public void setWarpingWindow(int warpingWindow) {
        this.warpingWindow = warpingWindow;
        warpingWindowInPercentage = false;
    }

    public double[][] getDistanceMatrix() {
        return distanceMatrix;
    }

    private void setDistanceMatrix(double[][] distanceMatrix) {
        if(keepDistanceMatrix) {
            this.distanceMatrix = distanceMatrix;
        }
    }

    public boolean isKeepDistanceMatrix() {
        return keepDistanceMatrix;
    }

    public void setKeepDistanceMatrix(final boolean keepDistanceMatrix) {
        this.keepDistanceMatrix = keepDistanceMatrix;
    }

    @Override
    public void clean() {
        super.clean();
        cleanDistanceMatrix();
    }

    public void cleanDistanceMatrix() {
        distanceMatrix = null;
    }

    @Override
    public double distance(Instance first, Instance second, final double limit,
        final PerformanceStats stats) {
        checkData(first, second);

        double minDist;
        boolean tooBig;

        int aLength = first.numAttributes() - 1;
        int bLength = second.numAttributes() - 1;

        // put a or first as the longest time series
        if(bLength > aLength) {
            Instance tmp = first;
            first = second;
            second = tmp;
            aLength = first.numAttributes() - 1;
            bLength = second.numAttributes() - 1;
        }

        /*  Parameter 0<=r<=1. 0 == no warpingWindow, 1 == full warpingWindow
         generalised for variable window size
         * */
        int windowSize = warpingWindow + 1; // + 1 to include the current cell
        if(warpingWindowInPercentage) {
            // warping window as double typed percentage
            if(warpingWindowPercentage < 0) {
                windowSize = aLength + 1;
            } else {
                windowSize = 
            }
        } else {
            // warping window as int
            if(warpingWindow < 0) {
                windowSize = aLength + 1;
            }
        }

        //Extra memory than required, could limit to windowsize,
        //        but avoids having to recreate during CV
        //for varying window sizes
        double[][] distanceMatrix = new double[aLength][bLength];
        setDistanceMatrix(distanceMatrix);

        /*
         //Set boundary elements to max.
         */
        int start, end;
        for(int i = 0; i < aLength; i++) {
            start = windowSize < i ? i - windowSize : 0;
            end = Math.min(i + windowSize + 1, bLength);
            for(int j = start; j < end; j++) {
                distanceMatrix[i][j] = Double.POSITIVE_INFINITY;
            }
        }
        distanceMatrix[0][0] = (first.value(0) - second.value(0)) * (first.value(0) - second.value(0));
        //a is the longer series.
        //Base cases for warping 0 to all with max interval	r
        //Warp first[0] onto all second[1]...second[r+1]
        for(int j = 1; j < windowSize && j < bLength; j++) {
            distanceMatrix[0][j] =
                distanceMatrix[0][j - 1] + (first.value(0) - second.value(j)) * (first.value(0) - second.value(j));
        }

        //	Warp second[0] onto all first[1]...first[r+1]
        for(int i = 1; i < windowSize && i < aLength; i++) {
            distanceMatrix[i][0] =
                distanceMatrix[i - 1][0] + (first.value(i) - second.value(0)) * (first.value(i) - second.value(0));
        }
        //Warp the rest,
        for(int i = 1; i < aLength; i++) {
            tooBig = true;
            start = windowSize < i ? i - windowSize + 1 : 1;
            end = Math.min(i + windowSize, bLength);
            if(distanceMatrix[i][start - 1] < limit) {
                tooBig = false;
            }
            for(int j = start; j < end; j++) {
                minDist = distanceMatrix[i][j - 1];
                if(distanceMatrix[i - 1][j] < minDist) {
                    minDist = distanceMatrix[i - 1][j];
                }
                if(distanceMatrix[i - 1][j - 1] < minDist) {
                    minDist = distanceMatrix[i - 1][j - 1];
                }
                distanceMatrix[i][j] =
                    minDist + (first.value(i) - second.value(j)) * (first.value(i) - second.value(j));
                if(tooBig && distanceMatrix[i][j] < limit) {
                    tooBig = false;
                }
            }
            //Early abandon
            if(tooBig) {
                return Double.POSITIVE_INFINITY;
            }
        }
        //Find the minimum distance at the end points, within the warping window.
        double distance = distanceMatrix[aLength - 1][bLength - 1];
        return distance;
    }

    @Override
    public ParamSet getParams() {
        return super.getParams().add(DTW.getWarpingWindowFlag(), getWarpingWindow());
    }

    @Override
    public void setParams(final ParamSet param) {
        ParamHandler.setParam(param, DTW.getWarpingWindowFlag(), this::setWarpingWindow, Integer.class);
    }
}
