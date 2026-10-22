package io.github.pgallo.blackboard.domain;

/**
 * Object classes of the synthetic scenario with the generative models used both by the
 * scenario generator and (as likelihoods) by the classifier.
 */
public enum ObjectClass {
    AIRCRAFT(190.0, 40.0, 12.0, false),
    VESSEL(7.0, 3.0, 28.0, true),
    FASTBOAT(18.0, 5.0, 6.0, true);

    /** Speed model in m/s. */
    public final double speedMean;
    public final double speedSd;
    /** Mean signature in dB (a stand-in for any scalar feature a sensor reports per detection). */
    public final double signatureMean;
    public final boolean surface;

    public static final double SIGNATURE_SD = 4.0;

    ObjectClass(double speedMean, double speedSd, double signatureMean, boolean surface) {
        this.speedMean = speedMean;
        this.speedSd = speedSd;
        this.signatureMean = signatureMean;
        this.surface = surface;
    }

    /** Log-likelihood of an observed speed and mean signature (n samples) under this class. */
    public double logLikelihood(double speed, double sigMean, int n) {
        double sigSd = SIGNATURE_SD / Math.sqrt(Math.max(1, n));
        return logNormal(speed, speedMean, speedSd) + logNormal(sigMean, signatureMean, sigSd);
    }

    private static double logNormal(double x, double mu, double sd) {
        double z = (x - mu) / sd;
        return -0.5 * z * z - Math.log(sd);
    }
}
