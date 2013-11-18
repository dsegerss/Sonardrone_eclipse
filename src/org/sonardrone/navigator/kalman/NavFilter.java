package org.sonardrone.navigator.kalman;
import org.ejml.simple.*;
import org.ejml.data.*;

public class NavFilter implements KalmanFilter{
    // kinematics description
    private SimpleMatrix F;
    private SimpleMatrix Q;
    private SimpleMatrix H;

    // sytem state estimate
    private SimpleMatrix x;
    private SimpleMatrix P;

    
    @Override
    public void configure(DenseMatrix64F F, DenseMatrix64F Q, DenseMatrix64F H) {
        this.F = new SimpleMatrix(F);
        this.Q = new SimpleMatrix(Q);
        this.H = new SimpleMatrix(H);
    }
    
    public void bypass(double[] newState) {
    DenseMatrix64F stateMatrix = new DenseMatrix64F(5,1, true, newState[0], newState[1], newState[2], newState[3],newState[4]);
    this.x=new SimpleMatrix(stateMatrix);
    }

    @Override
    public void setState(DenseMatrix64F x, DenseMatrix64F P) {
        this.x = new SimpleMatrix(x);
        this.P = new SimpleMatrix(P);
    }

    @Override
    public void predict() {
    	//predicted state
        // x = F x
        x = F.mult(x);
//        System.out.println("predicted x:"+x.toString());
        //Predicted covariance
        // P = F P F' + Q
        P = F.mult(P).mult(F.transpose()).plus(Q);
//        System.out.println("predicted P:"+P.toString());
    }

    @Override
    public void update(DenseMatrix64F _z, DenseMatrix64F _R) {
        // a fast way to make the matrices usable by SimpleMatrix
        SimpleMatrix z = SimpleMatrix.wrap(_z);
        SimpleMatrix R = SimpleMatrix.wrap(_R);

        // y = z - H x
        SimpleMatrix y = z.minus(H.mult(x));

        // S = H P H' + R
        SimpleMatrix S = H.mult(P).mult(H.transpose()).plus(R);

        // K = PH'S^(-1)
        SimpleMatrix K = P.mult(H.transpose().mult(S.invert()));
        
        // x = x + Ky
        x = x.plus(K.mult(y));

        // P = (I-kH)P = P - KHP
        P = P.minus(K.mult(H).mult(P));
    }
    
    public void partialUpdate(boolean[] mask, DenseMatrix64F _z, DenseMatrix64F _R) {    	
    	
    	int nvals=0;
    	for(int i=0;i<mask.length;i++) {
    		if(mask[i])
    			nvals++;
    	}
    	
    	//Create subsets of full kalman matrices
    	DenseMatrix64F _zp = new DenseMatrix64F(nvals,1);
    	DenseMatrix64F _Rp = new DenseMatrix64F(nvals,nvals);
    	DenseMatrix64F _Hp = new DenseMatrix64F(nvals,H.numCols());
    	
    	//Fill matrices with data
    	int row=0;
    	int col=0;
    	for(int i=0;i<mask.length;i++) {
    		if(mask[i]) {
    			col=0;
    			for(int j=0;j<mask.length;j++) {
    				if (mask[j]) {
    					_Rp.set(row,col,_R.get(i,j));
    					col++;
    				}
    			}
        		row+=1;
        	}
        }
    	
    	row=0;
    	for(int i=0;i< mask.length;i++) {    		
    		if(mask[i]) {
    			_zp.set(row,_z.get(i));
    			row++;
    		}
    	}
    	
    	row=0;
    	for(int i=0;i<mask.length;i++) {
    		if(mask[i]) {
    			for(int j=0;j<H.numCols();j++)
    				_Hp.set(row,j,H.get(i,j));
				row++;
    		}
    	}

        // a fast way to make the matrices usable by SimpleMatrix
        SimpleMatrix z = SimpleMatrix.wrap(_zp);
        SimpleMatrix R = SimpleMatrix.wrap(_Rp);
        SimpleMatrix Hp = SimpleMatrix.wrap(_Hp);

        // y = z - H x
        SimpleMatrix y = z.minus(Hp.mult(x));
        
        // S = H P H' + R
        SimpleMatrix S = Hp.mult(P).mult(Hp.transpose()).plus(R);

        // K = PH'S^(-1)
        SimpleMatrix K = P.mult(Hp.transpose().mult(S.invert()));
        // x = x + Ky
        x = x.plus(K.mult(y));

        // P = (I-kH)P = P - KHP
        P = P.minus(K.mult(Hp).mult(P));
//        System.out.println("updated P:"+P.toString());
    	
    }
    

    @Override
    public DenseMatrix64F getState() {
        return x.getMatrix();
    }

    @Override
    public DenseMatrix64F getCovariance() {
        return P.getMatrix();
    }
}