package de.fhg.fokus.diameter.DiameterPeer.transport;

public class CommonMethod {
	
	static final char[] hexChar = { '0', '1', '2', '3', '4', '5', '6', '7',
		'8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };
	//十六进制字符串到byte数组
	public static byte[] hexToByte(String string) {
		byte[] bts = new byte[string.length() / 2];
		for (int i = 0; i < bts.length; i++) {
			bts[i] = (byte) Integer.parseInt(
					string.substring(2 * i, 2 * i + 2), 16);
		}
		return bts;
	}
	//byte数组到十六进制字符串
	public static String byteToHex(byte[] bytes) {
		StringBuffer sb = new StringBuffer();
		for (int i = 0; i < bytes.length; i++) {
			//look up high nibble
			sb.append(hexChar[(bytes[i] & 0xf0) >>> 4]);
			//look up low nibble
			sb.append(hexChar[bytes[i] & 0x0f]);
			//add white space
			//sb.append(" ");
			//add Enter
			/*if (i != 0 && i % 40 == 0) {
			    sb.append("\n");
			}*/
		}
		return sb.toString();
	}
	//从字符串(ASCII)到二进制
	public static String stringToBinary(String str) {
        StringBuffer str2=new StringBuffer();
        for(int i=0;i<str.length();i++) {
        	str2=str2.append(fill_zero(Integer.toBinaryString(Integer.valueOf(str.charAt(i))),8));
        }
    return str2.toString();
    }
    //按位填充函数
    public static String fill_zero(String str,int n) {
        String str2 = new String();
        StringBuffer str1 = new StringBuffer();
        
        if(str.length() < n)
            for(int i=0; i < n-str.length(); i++) {
            str2 = str1.append('0').toString();
        }
        return str2+str;//Integer.toHexString(Integer.valueOf(str2+s,2))+" ";//换成十六进制;
        }
    //按位异或
    public static String bit_df_or(String str1,String str2) {
        String str=new String();
        StringBuffer s=new StringBuffer();
        for(int i=0;i<str1.length();i++) {
            if(str1.charAt(i)==str2.charAt(i))
                str=s.append('0').toString();
            else
                str=s.append('1').toString();
        }
        return str;
    }
    //按位同或
    public static String bit_sa_or(String str1,String str2) {
        String str=new String();
        StringBuffer s=new StringBuffer();
        for(int i=0;i<str1.length();i++) {
            if(str1.charAt(i)==str2.charAt(i))
                str=s.append('1').toString();
            else
                str=s.append('0').toString();
        }
        return str;
    }
    //按位与
    public static String bit_and(String str1,String str2) {
        String str=new String();
        StringBuffer s=new StringBuffer();
        for(int i=0;i<str1.length();i++) {
            if(str1.charAt(i)=='0'||str2.charAt(i)=='0')
                str=s.append('0').toString();
            else
                str=s.append('1').toString();
        }
        return str;
    }
    //按位或
    public static String bit_or(String str1,String str2) {
        String str=new String();
        StringBuffer s=new StringBuffer();
        for(int i=0;i<str1.length();i++) {
            if(str1.charAt(i)=='1'||str2.charAt(i)=='1')
                str=s.append('1').toString();
            else
                str=s.append('0').toString();
        }
        return str;
    }
    //按位非
    public static String bit_non(String str1) {
        String str=new String();
        StringBuffer s=new StringBuffer();
        for(int i=0;i<str1.length();i++) {
            if(str1.charAt(i)=='0')
                str=s.append('1').toString();
            else
                str=s.append('0').toString();
        }
        return str;
    }
    //按位循环左移n位
    public static String rotl(String str,int n) {
        String str1=str.substring(0,n);
        String str2=str.substring(n);
        return str2+str1;
    }
    //按位循环右移n位
    public static String rotr(String str,int n) {
        String str1=str.substring(str.length()-n);
        String str2=str.substring(0,str.length()-n);
        return str1+str2;
    }
    //按位右移n位
    public static String shr(String str,int n) {
        char[] fillZero=new char[n];
        java.util.Arrays.fill(fillZero,'0');
        String str1=str.substring(0,str.length()-n);
        return new String(fillZero)+str1;
    }

    public static String ch(String str1,String str2,String str3) {
        return bit_df_or(bit_and(str1,str2),bit_and(bit_non(str1),str3));
    }
    
    public static String maj(String str1,String str2,String str3) {
        return bit_df_or(bit_df_or(bit_and(str1,str2),bit_and(str1,str3)),bit_and(str2,str3));
    }
    
    public static String big_sigma_zero(String str1) {
        return bit_df_or(bit_df_or(rotr(str1,2),rotr(str1,13)),rotr(str1,22));
    }
    
    public static String big_sigma_one(String str1) {
        return bit_df_or(bit_df_or(rotr(str1,6),rotr(str1,11)),rotr(str1,25));
    }

    public static String small_sigma_zero(String str1) {
        return bit_df_or(bit_df_or(rotr(str1,7),rotr(str1,18)),shr(str1,3));
    }

    public static String small_sigma_one(String str1) {
        return bit_df_or(bit_df_or(rotr(str1,17),rotr(str1,19)),shr(str1,10));
    }
    

    //按位相加
    public static String binaryplus(String str1,String str2) {
        char[] cstr=new char[48];
        int flag=0;
        for(int i=str1.length()-1;i>=0;i--) {
            cstr[i]=(char)(((str1.charAt(i)-'0')+((str2.charAt(i)-'0'))+flag)%2+'0');
            if(((str1.charAt(i)-'0')+(str2.charAt(i)-'0')+flag)>=2)
                flag=1;
            else
                flag=0;
        }
        return new String(cstr);
    }
    //二进制到十六进制
    public static String binaryToHex(String str) {
        int temp=0;
        //String str1=new String();
        StringBuffer st=new StringBuffer(); 
        for(int i=0;i<str.length()/4;i++) {
            temp=Integer.valueOf(str.substring(i*4,(i+1)*4),2);
            st=st.append(Integer.toHexString(temp));
        }
        return st.toString();
    }
    //从十六进制到二进制
    public static String hexToBinary(String str) {
        StringBuffer st1=new StringBuffer();
        String st=new String(); 
        for(int i=0;i<str.length();i++){
            switch(str.charAt(i)) {
                case '0':st="0000";
                	break;
                case '1':st="0001";
                	break;
                case '2':st="0010";
                	break;
                case '3':st="0011";
                	break;
                case '4':st="0100";
                	break;
                case '5':st="0101";
                	break;
                case '6':st="0110";
                	break;
                case '7':st="0111";
                	break;
                case '8':st="1000";
                	break;
                case '9':st="1001";
                	break;
                case 'a':
                case 'A':
                	st="1010";
                	break;
                case 'b':
                case 'B':
                	st="1011";
                	break;
                case 'c':
                case 'C':
                	st="1100";
                	break;
                case 'd':
                case 'D':
                	st="1101";
                	break;
                case 'e':
                case 'E':
                	st="1110";
                	break;
                case 'f':
                case 'F':
                	st="1111";
                	break;
                default :
                	break;
            }
        st1=st1.append(st);    
    }
    return st1.toString();
    }
    //计算T1
    public static String T1(String str_h,String str_e,String str_ch,String str_w,String str_k) {
        return binaryplus(binaryplus(binaryplus(str_h,big_sigma_one(str_e)),binaryplus(str_ch,str_w)),str_k);
    }
    //计算T2
    public static String T2(String str_a,String str_maj) {
        return binaryplus(big_sigma_zero(str_a),str_maj);
    }
}
