package peergos.shared.util;
import java.util.logging.*;

public class StringUtils {
	private static final Logger LOG = Logger.getGlobal();
	
    public static String format(String format, Object ... args) {
		return sprintf(format, args);
    }
    
	//from jDosbox
    public static String sprintf(String format, Object[] args) {
        int pos = format.indexOf('%');
        if (pos>=0) {
            StringBuffer buffer = new StringBuffer();
            int argIndex = 0;
            while (pos>=0) {
                buffer.append(format.substring(0, pos));
                if (pos+1<format.length()) {
                    char c = format.charAt(++pos);
                    if (c == '%') {
                        buffer.append("%");
                        format = format.substring(2);
                    } else {
                        boolean leftJustify = false;
                        boolean showPlus = false;
                        boolean spaceSign = false;
                        boolean prefix = false;
                        boolean leftPadZero = false;
                        int width = 0;
                        int precision = -1;

                        // flags
                        while (true) {
                            if (c=='-') {
                                leftJustify = true;
                            } else if (c=='+') {
                                showPlus = true;
                            } else if (c==' ') {
                                spaceSign = true;
                            } else if (c=='#') {
                                prefix = true;
                            } else if (c=='0') {
                                leftPadZero = true;
                            } else {
                                break;
                            }
                            if (pos+1<format.length()) {
                                c = format.charAt(++pos);
                            } else {
                                return buffer.toString();
                            }
                        }

                        // width
                        String w = "";
                        while (true) {
                            if (c>='0' && c<='9') {
                                w+=c;
                            } else {
                                break;
                            }
                            if (pos+1<format.length()) {
                                c = format.charAt(++pos);
                            } else {
                                return buffer.toString();
                            }
                        }
                        if (w.length()>0) {
                            width = Integer.parseInt(w);
                        }

                        // precision
                        if (c=='.') {
                            if (pos+1<format.length()) {
                                c = format.charAt(++pos);
                            } else {
                                return buffer.toString();
                            }

                            String p = "";
                            while (true) {
                                if (c>='0' && c<='9') {
                                    p+=c;
                                } else {
                                    break;
                                }
                                if (pos+1<format.length()) {
                                    c = format.charAt(++pos);
                                } else {
                                    return buffer.toString();
                                }
                            }
                            if (p.length()>0) {
                                precision = Integer.parseInt(p);
                            }
                        }

                        // length
                        if (c=='h') {
                            if (pos+1<format.length()) {
                                c = format.charAt(++pos);
                            } else {
                                return buffer.toString();
                            }
                        } else if (c=='l') {
                            if (pos+1<format.length()) {
                                c = format.charAt(++pos);
                            } else {
                                return buffer.toString();
                            }
                        } else if (c=='L') {
                            if (pos+1<format.length()) {
                                c = format.charAt(++pos);
                            } else {
                                return buffer.toString();
                            }
                        }

                        String value = "";
                        String strPrfix = "";
                        boolean negnumber = false;
                        if (c == 'c') {
                            if (args[argIndex] instanceof Character) {
                                value = String.valueOf(args[argIndex]);
                            } else if (args[argIndex] instanceof String) {
                                value = (String)args[argIndex];
                            } else {
                                LOG.info("Invalid printf argument type for %c: "+args[argIndex].getClass());
                                return buffer.toString();
                            }
                            if (value.length()>1)
                                value = value.substring(0,1);
                        } else if (c == 's') {
                            if (args[argIndex] instanceof Character) {
                                value = String.valueOf(args[argIndex]);
                            } else if (args[argIndex] instanceof String) {
                                value = (String)args[argIndex];
                            } else {
                                LOG.info("Invalid printf argument type for %s: "+args[argIndex].getClass());
                                return buffer.toString();
                            }
                            if (precision>0 && value.length()>precision) {
                                value = value.substring(0,precision);
                            }
                        } else if (c == 'x') {
                            if (args[argIndex] instanceof Integer) {
                                value = Integer.toString(((Integer)args[argIndex]).intValue(), 16);
                            } else if (args[argIndex] instanceof Long) {
                                value = Long.toString(((Long)args[argIndex]).longValue(), 16);
                            } else {
                                LOG.info("Invalid printf argument type for %x: "+args[argIndex].getClass());
                                return buffer.toString();
                            }
                            negnumber = value.startsWith("-");
                            if (negnumber)
                                value = value.substring(1);
                            if (precision==0 && value.equals("0")) {
                                format = format.substring(pos);
                                continue;
                            }
                            if (prefix) {
                                strPrfix += "0x"+value;
                            }
                        } else if (c == 'X') {
                            if (args[argIndex] instanceof Integer) {
                                value = Integer.toString(((Integer)args[argIndex]).intValue(), 16);
                            } else if (args[argIndex] instanceof Long) {
                                value = Long.toString(((Long)args[argIndex]).longValue(), 16);
                            } else {
                                LOG.info("Invalid printf argument type for %X: "+args[argIndex].getClass());
                                return buffer.toString();
                            }
                            negnumber = value.startsWith("-");
                            if (negnumber)
                                value = value.substring(1);
                            if (precision==0 && value.equals("0")) {
                                format = format.substring(pos);
                                continue;
                            }
                            if (precision>0) {
                                while (value.length()<precision) {
                                    value = "0"+value;
                                }
                            }
                            value = value.toUpperCase();
                            if (prefix) {
                                strPrfix += "0X"+value;
                            }
                        } else if (c == 'd') {
                            if (args[argIndex] instanceof Integer) {
                                value = Integer.toString(((Integer)args[argIndex]).intValue());
                            } else if (args[argIndex] instanceof Long) {
                                value = String.valueOf(((Long)args[argIndex]).longValue());
                            } else {
                                LOG.info("Invalid printf argument type for %d: "+args[argIndex].getClass());
                                return buffer.toString();
                            }
                            negnumber = value.startsWith("-");
                            if (negnumber)
                                value = value.substring(1);
                            if (precision==0 && value.equals("0")) {
                                format = format.substring(pos);
                                continue;
                            }
                            if (precision>0) {
                                while (value.length()<precision) {
                                    value = "0"+value;
                                }
                            }
                        } else if (c == 'f') {
                            if (args[argIndex] instanceof Double) {
                                value = String.valueOf(((Double)args[argIndex]).doubleValue());
                            } else if (args[argIndex] instanceof Float) {
                                value = String.valueOf(((Float)args[argIndex]).doubleValue());
                            } else {
                                LOG.info("Invalid printf argument type for %f: "+args[argIndex].getClass());
                                return buffer.toString();
                            }
                            negnumber = value.startsWith("-");
                            if (negnumber)
                                value = value.substring(1);
                            int dec = value.indexOf('.');
                            if (dec>=0) {
                                if (precision==0) {
                                    value = value.substring(0, dec);
                                } else if (value.length()>dec+1+precision) {
                                    value = value.substring(0, dec+1+precision);
                                }
                            }
                        }

                        if (negnumber) {
                            strPrfix = "-";
                        } else {
                            if (showPlus) {
                                strPrfix = "+"+strPrfix;
                            } else if (spaceSign) {
                                strPrfix = " "+strPrfix;
                            }
                        }
                        while (width>strPrfix.length()+value.length()) {
                            if (leftPadZero) {
                                strPrfix+="0";
                            } else if (leftJustify) {
                                value=value+" ";
                            } else {
                                strPrfix=" "+strPrfix;
                            }
                        }
                        buffer.append(strPrfix);
                        buffer.append(value);
                        format = format.substring(++pos);
                    }
                }
                argIndex++;
                pos = format.indexOf('%');
            }
            buffer.append(format);
            return buffer.toString();
        } else {
            return format;
        }
    }
}
