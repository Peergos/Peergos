package peergos.crypto;

import org.bouncycastle.util.encoders.Base64;

public class CoreCertificates {
    public static final int NUM_SERVERS = 1;
    public static byte[][] servers = new byte[NUM_SERVERS][];
    static {
        servers[0] = Base64.decode(            "MIIG6zCCBNOgAwIBAgKBgQDbMVsDVZeqvf0uJnxfKZImClbC/E4lXWk8drh9MKPNlJD53f" +
            "OfNQg9E7jL+V68EpzQtfwlY8D+fZmFUWTlwq05fW+rZCwYGNssGEwXK8h0o5jONgQ30sbd" +
            "kucdNlHmvjI2O63Q4H25AWH+udjMBvq3hmJ9nVffil6hsFrWM/69/zANBgkqhkiG9w0BAQ" +
            "sFADCBiTEQMA4GA1UEAwwHUGVlcmdvczELMAkGA1UEBhMCQVUxEDAOBgNVBAoMB1BlZXJn" +
            "b3MxEjAQBgNVBAcMCU1lbGJvdXJuZTERMA8GA1UECAwIVmljdG9yaWExLzAtBgkqhkiG9w" +
            "0BCQEMIGhlbGxvLk5TQS5HQ0hRLkFTSU9AZ29vZGx1Y2suY29tMB4XDTE1MTIyNDA2NTE1" +
            "NVoXDTE2MDExMDA3MzIyNFowgYsxEjAQBgNVBAMMCWxvY2FsaG9zdDELMAkGA1UEBhMCQV" +
            "UxEDAOBgNVBAoMB1BlZXJnb3MxEjAQBgNVBAcMCU1lbGJvdXJuZTERMA8GA1UECAwIVmlj" +
            "dG9yaWExLzAtBgkqhkiG9w0BCQEMIGhlbGxvLk5TQS5HQ0hRLkFTSU9AZ29vZGx1Y2suY2" +
            "9tMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAySx/QWET8YcLZAvHl7HycZNU" +
            "VTOb9jRN1abQrge05R3VCCCFNMgtcBFqYnrCuS9kfYPUQJvgNywC2XObPEICeqC63TGksy" +
            "Zg98WwFze2snA9YzKRECTjPkFVGcA06ahkjBelwkxGzfQZL+xw752/yPojBU2FU8dzlcXW" +
            "fZ3KLtttcqokp5XJJ9MlkkNN0FTpr7tvK1U5SSfZfRcxWwnv0oJ9AnDihC+yDaz7EOnpKE" +
            "CCzuCCnyd8yoF5uNjQWt0znUbFGVrfz4+6XPta7ZRJy05qY3H1erQRItmdz8JhOvE9Msik" +
            "MYGmjOGKZIhyxFZYw2vOT+Wl/scSixM0JuMrE0rbnKrI6yjH9m5xgRbPMvW2xVaEyCoelc" +
            "Ol4R6yVJJ8QHYcpTj1YgUOqQOr1U3PZeBFWFxG6oiO/r6BHUtTP3876R7Ncl3BIzbXAvSF" +
            "TDiRNeI5A0ZEUSiS+KJyqIKJjMMzW63IF2JlfKvGLFA70QtdkyjzZTibyNsW5I15zVrZuA" +
            "ASsSjzczyeJ1htzVsgse//nfYxckg4UYpq6u5dKFSRP4QcLQNtJQUBYUl8A9PZqfgmgACz" +
            "zRiBh92/Hq7vmTDpFGzc9Q4xgPPEa95x2f3xpFL56r8LCRjyYKT9AXmvtAMegzvMJ6heTq" +
            "CES1bs7AeapsEubv8iVxszXizJsHMCAwEAAaOB2DCB1TCBvgYDVR0jBIG2MIGzgBSeOep0" +
            "zEZrGgwQkAD6GkC2+CZQHaGBj6SBjDCBiTEQMA4GA1UEAwwHUGVlcmdvczELMAkGA1UEBh" +
            "MCQVUxEDAOBgNVBAoMB1BlZXJnb3MxEjAQBgNVBAcMCU1lbGJvdXJuZTERMA8GA1UECAwI" +
            "VmljdG9yaWExLzAtBgkqhkiG9w0BCQEMIGhlbGxvLk5TQS5HQ0hRLkFTSU9AZ29vZGx1Y2" +
            "suY29tggkAo9zaEflZCncwEgYDVR0TAQH/BAgwBgEB/wIBADANBgkqhkiG9w0BAQsFAAOC" +
            "AgEAVGPJUzoOOdSMeji1FAbiBm/a4wCplc8guwbZOSJpO5vGvKoI2z0FbB6vCZaP8VYTni" +
            "VvC/ubO1NvRu+WZFKOgteErtjKwBbwddWaRtzyVb+1KLn1VHnbCBvQtnfswotQ3cyiAXuX" +
            "ByZGNYwkYlXA5P/ySh5Ud57WrQ5rpjMKrcdLT8/QqxBS1HNTXuA3cgLJxNBR8EDd8b0f8D" +
            "Tp78XVkSFtzR/oUXOGLPVJuo3xxnyv37vMHRacwMg8jzAHxS7YNPMGM5xbgGEeikNSLGzL" +
            "aARorqBbucT2lWEOvXa+QskYPweBFgL+lipYUeKmdQlnLul2dzX00xcHVbTDmSs0TVOFXI" +
            "mObensmv1hqZ8NCATKZbE1HFYfFN2RwclF29I4nQ1TRcaRHxt9Jx5fhlgfL4lIT40+rql5" +
            "D69B7Yv4iKZndaxEWU49MvJXEPdrymLd84sg7StFEzy7MfMDoN/9Fs+eDLv7tiz4o2RKS8" +
            "qHKEDn3kWXMxX1ZWUiCIEy3S5hF7BuQdNb2dSJl9G9J8Hlh+TU05V9r+ZCDRIVtjvewPPL" +
            "of+ybi5RJ5P54ee0giDwpUHIN/EkDG28BJ4emWdi7C3QPQhyxuCwewhvx5GuMPqHoTovCX" +
            "GKklAoV4pqOOMvAYsKhUBQv+CHPR3G6t7iR3f3pRKtuF41uce1eByooDc=" );
    }
}