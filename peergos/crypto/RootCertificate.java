package peergos.crypto;

import org.bouncycastle.util.encoders.Base64;

public class RootCertificate {
<<<<<<< HEAD
    public static final byte[] rootCA = Base64.decode(            "MIIFszCCA5ugAwIBAgIIEIO7ZbIQOGkwDQYJKoZIhvcNAQELBQAwgY4xFTATBgNVBAMMDD" +
            "E5Mi4xNjguMS41MTELMAkGA1UEBhMCQVUxEDAOBgNVBAoMB1BlZXJnb3MxEjAQBgNVBAcM" +
            "CU1lbGJvdXJuZTERMA8GA1UECAwIVmljdG9yaWExLzAtBgkqhkiG9w0BCQEMIGhlbGxvLk" +
            "5TQS5HQ0hRLkFTSU9AZ29vZGx1Y2suY29tMB4XDTE0MDQxNjE0MTM0MVoXDTE1MDQxNjE0" +
            "MTM0MVowgY4xFTATBgNVBAMMDDE5Mi4xNjguMS41MTELMAkGA1UEBhMCQVUxEDAOBgNVBA" +
            "oMB1BlZXJnb3MxEjAQBgNVBAcMCU1lbGJvdXJuZTERMA8GA1UECAwIVmljdG9yaWExLzAt" +
            "BgkqhkiG9w0BCQEMIGhlbGxvLk5TQS5HQ0hRLkFTSU9AZ29vZGx1Y2suY29tMIICIjANBg" +
            "kqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAyWFm7kBDIjZKh8EMTfMCHzYwFS1qs5zG3jOp" +
            "8m8bXhBfe/AU1O8Dx9N2aFcDOdjzddKSXteFCpz8sxvtnt6IJHLIkOzEdNalrCtWLHlNsl" +
            "EuvDyA7zWnKkuyM1oeJD7743Nb7+0uETwxIBvEYmMCcErmRgE+L6bonuVnNJ4uUyoIScET" +
            "/VAN2dKeC7R2BCVqD4yt4c9DvylsRB6zcvFJ7fk8cQ697o92IPnfy94oLxLB08SS702xEK" +
            "Ui6gJYvEXMfCyyplLQYEfB8xLoBPcEnVsUwijMrkhzHE6GpcM+Y7N1wdKsXLm4FnoM0Q10" +
            "mg2Z17W58FxeRmGgAQT5SUP/pTKLqVhyG1NUHYzktRZFvNojHwCY/Ky0BdxVd5HFTM683s" +
            "eVPGV0RJlVSlbWUyEcvNpEFy45VfqaChlN74I1jCFOCwKEWMcpCsRTYAw5aSwRUnyqrFTS" +
            "fCpSnmkIJ91NNP043snv2zmrrrXDmsCEERqTrd24qNHIuVC1AkngWImvvP5ukjTP8X7iK8" +
            "66arlG9Dl6tnVXWbKvnDeq1sv2aLEHZIpETyUKw12sSpyHcyAuZtihLfcb7mxmEvaLHxez" +
            "dse37gNU6TF08m599xq5giw1pllA5IoJEIp1RVoFwV7V5+dTTI9R39mHPB1K54bspBN/y2" +
            "0fkEAXxg4AWy5LoPECAwEAAaMTMBEwDwYDVR0RBAgwBocEwKgBMzANBgkqhkiG9w0BAQsF" +
            "AAOCAgEAgqlTLWfVLvHvkd3OIjUE3ANxXJNc3FmH/4b7SHOCRkxE48qV8xlXdMlZD4LL/v" +
            "43knBMptX4IZTbZnuMkeDIZnAkfDXS6sCbSg6zIDe/Oaaz4PngJhVE8bb3qcomKvXs3G2O" +
            "iFKm5T5UjXk4OYklc/0Jo+evIYa2kKb5dtUorjgkQQarrfs/iulO2dxYie9kxbYgYWvNpL" +
            "NuYQYzaBojJ5wc8ccRPF5yQCaxx0572LKE0R7Zbr5mCJvAky55ozj5WQyNgUG+uZUTXFXp" +
            "YsRLUp8fhlJUJRIuJk1RsuNeq+finMhY9LNxk8EXDN+kSZpMRILK0uDDsJUsHEgOIkyy5M" +
            "NSZuijM2ipfQdxEdfo4W+vUY2SKD6sIZ/C23tr5aMcHw6xrQOGrrU7O7OVxR5HztSNcx8h" +
            "xIpSRfr34woLmpBtL66KPtecYgypaGmX+MY7MPLic3snnvXGd3CLY46JB56WIQm0Ol1nhX" +
            "a1rSBsl2lVjPSj5OGg4Lw2v5iTEsB9dhPztNhOigY93o/VqFQxkjEG20VTr4aiM0NjASy0" +
            "1/GoS/UMKQSZcsKIk4VZR8tc+keQIM4c7rTQDgxBLfK687qdiDVY4fNJEwlHJGkIc9+Q+M" +
            "NmOuUrN9qp2LWBw1TlsPFZT6wt4+PsjBi82IMuTPzEf2dkCUn91DJkzTElYec=" );
=======
    public static final byte[] rootCA = Base64.decode(            "MIIFrTCCA5WgAwIBAgIITLTFySFHVY4wDQYJKoZIhvcNAQELBQAwgYsxEjAQBgNVBAMMCT" +
            "EwLjAuMi4xNTELMAkGA1UEBhMCQVUxEDAOBgNVBAoMB1BlZXJnb3MxEjAQBgNVBAcMCU1l" +
            "bGJvdXJuZTERMA8GA1UECAwIVmljdG9yaWExLzAtBgkqhkiG9w0BCQEMIGhlbGxvLk5TQS" +
            "5HQ0hRLkFTSU9AZ29vZGx1Y2suY29tMB4XDTE0MDQxNjE0MDgxOVoXDTE1MDQxNjE0MDgx" +
            "OVowgYsxEjAQBgNVBAMMCTEwLjAuMi4xNTELMAkGA1UEBhMCQVUxEDAOBgNVBAoMB1BlZX" +
            "Jnb3MxEjAQBgNVBAcMCU1lbGJvdXJuZTERMA8GA1UECAwIVmljdG9yaWExLzAtBgkqhkiG" +
            "9w0BCQEMIGhlbGxvLk5TQS5HQ0hRLkFTSU9AZ29vZGx1Y2suY29tMIICIjANBgkqhkiG9w" +
            "0BAQEFAAOCAg8AMIICCgKCAgEAh1M0J85AVUM7fHrra9XhHbzq7T8470e2niZgTslCb+BI" +
            "NEN8hswCCn3y9Eexwi2ku6dnIeWPzzuEAH/x2XAhxQ2KLfLlC1lfqqHnjd5w4NMvNmny7m" +
            "y9BuOU6xB5gSMpUAzcwhwHGRizTpME3R71VSjC7WL47jFfdq4Fdg2UCnd/87D/IsCAcdqC" +
            "V8L9GCKqkjNmjIookfyDFicaihvHeGPUNKYHzttkGiYNxWcoGBiTpvoyCMMW9mNJJ9guPk" +
            "64TzxuRx6BzoYs4njISymG41+imSVyWpOoZ1vSt7I+6yoovEc/TvNyDwJQK3P6kI4g9pqH" +
            "x5m1dHwjr733iSy32/EO2w6C02afQj2+SBDX5G2claGjKBbUJ7lVW3MgM2lAyMVZIFBOyk" +
            "i09CKhSkmc7o/CTVN4IZbrGp9NsUF9YZ959fCjo0uYLM64uHsrQHI6Mds18LjYlBa7SWyI" +
            "5n5ZZ6oVyYCXn9y8E//FPzYIvdzqAXa86m0sggsZc7cd+Qi6mIK+7qsQ9dSDb2GwFEZggQ" +
            "Wz1qmJaKtzEf9GJcMYZX2V2XaSeTUkdXaz6C3jsvP5RFSrzwSrgiFSUfA/ZLlOQjSUjiwK" +
            "MGTiqv9wk6hDcsfZ2TRv5hbK8sEdtKeECN6a8KXtAwIBuWW5u6Hj7gEbj2EOKNJiVuUtz+" +
            "M7yzHbFhcCAwEAAaMTMBEwDwYDVR0RBAgwBocECgACDzANBgkqhkiG9w0BAQsFAAOCAgEA" +
            "IwXLcJ0rnuWOtPoW1qh/lsqG0O40B0WscQ9jjHoUWAwHcaYxOYGCuZRR77SWIedqRzHgJL" +
            "tUrcZKyqUNuFOtxsRz4haf9xkcoLQP31hYakCF53/t3xEFsN2NILl0FeY1eHs186RLDXUq" +
            "0xQWO4duXlHn4PXpITodj7W49IfobUQJ/IqBxH8aCr9/R6fIyTqdApLwzrfN/sRMx3C+L0" +
            "hiOIxNDzny9ajhpwlqFj0Ys8pdDMM7VKi2LlUroNc1hYiBQ2xad0qXqeWs/mEeRq+qh5m5" +
            "RHQNM1vpE2a4TEE68DPduw/qiyCQctO4uvGBZauo8FoE596uP79mUfjUYGLMgQ0MpFdPJF" +
            "BvziR7+he19RgcQ4EeeYDELlU3dW5qw01GcbsvFcaofYcG77vraFSu83qHlipiq6O5v9nO" +
            "eVj98UUl9JVv5vAI2R+vc+PqcgcdBaJeTkN+ADtjhM/cm6/aSeENlsnOvWwVKSLYsqk1JW" +
            "rw+sMNCS2OJYtSAYnlqqUKGevb/Bt80qY0kHGh6x0/boP11I2l9Debi+KS9PAopcabwKpO" +
            "PJfRlvjbdwKOQ3/OUPlRWm23wBffGV7QgBJgg1zqHco1OHBDqR4ZbnPAnMHKlvOQ4ap+tw" +
            "BVnE9lD5UyAFHzznKwqJC5cHwd1b5uJXIaB4lSnXrwQduIfnSQkqk=" );
>>>>>>> 96fa7beb9c093c6262fd40c4dc51f83c00270fbd
}