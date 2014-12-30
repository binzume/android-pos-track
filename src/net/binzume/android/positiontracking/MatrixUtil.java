package net.binzume.android.positiontracking;

/**
 * 4x4 OpenGL matrix util.
 */
public class MatrixUtil {

	/**
	 * @param mat 4x4 matrix
	 */
	public static String stringfy(float[] mat) {
		String s = "";
		for (int i = 0; i < 4; i++) {
			s += "| " + mat[i * 4 + 0] + "," + mat[i * 4 + 1] + "," + mat[i * 4 + 2] + "," + mat[i * 4 + 3] + " ";
		}
		return s + "|";
	}

	public static void multiplyVM(float[] ret, float lhsVec[], float rhsMat[]) {
		for (int i = 0; i < 4; i++) {
			ret[i] = lhsVec[0] * rhsMat[i] + lhsVec[1] * rhsMat[i + 4] + lhsVec[2] * rhsMat[i + 8] + lhsVec[3] * rhsMat[i + 12];
		}
	}

	public static void copy(float[] dst, float src[]) {
		System.arraycopy(src, 0, dst, 0, 16);
	}

}
