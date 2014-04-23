package peergos.crypto;

import org.bouncycastle.util.encoders.Base64;

public class DirectoryCertificates {
    public static final int NUM_DIR_SERVERS = 1;
    public static byte[][] directoryServers = new byte[NUM_DIR_SERVERS][];
    static {
<<<<<<< HEAD
        directoryServers[0] = Base64.decode(            "MIIFLjCCAxagAwIBAgIBATANBgkqhkiG9w0BAQsFADARMQ8wDQYDVQQDDAZpc3N1ZXIwHh" +
            "cNMTQwNDE2MTQxNDA0WhcNMTQwNDMwMDgwMDM5WjCBjjEVMBMGA1UEAwwMMTkyLjE2OC4x" +
            "LjUxMQswCQYDVQQGEwJBVTEQMA4GA1UECgwHUGVlcmdvczESMBAGA1UEBwwJTWVsYm91cm" +
            "5lMREwDwYDVQQIDAhWaWN0b3JpYTEvMC0GCSqGSIb3DQEJAQwgaGVsbG8uTlNBLkdDSFEu" +
            "QVNJT0Bnb29kbHVjay5jb20wggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQC23P" +
            "ubjV587eRUQwrKvuYqs6D/ggdEvp8rVDrjmcvxoFNkj05Ei07M57Vwza1Y/c4mVoQIcXnx" +
            "X8ck5vqb3mGwqwHzF+c4kf0Sp+Ck/57+4YyK3YC25rQ4MceUopcrhS6onuJ5vT+Lppl6lX" +
            "Easgyr0VuPya8tK2fWcZYADpfOJJnDAywbptarGFmjJaNpCOtOQGAyZNyirDgrzrkhWKCe" +
            "QqlXh+vdFNZ/31gsJnN+CEJsxOYo8QV2uQCVCfkOL4PeunwyWLLK71k6/idLAJQR5XDdsn" +
            "KGwEDzk0/5HIJrezrRCBgsQ8TPKk4hAcyBWztgwvvwRo/5SUifz+pZZUGQvEFFrhdJ0CqG" +
            "38ji30hbyaZt523UajZ/rBpJCJwcWe62HzZutSeoieDxZt0G6PSlOvA/7T0iYLwQi6gevL" +
            "R5wpeM84ImkbJwUmS7uRnA+oJOFjhzfFjnUsGrPh0+UeH2wplzt2t0ZlgFiDPxitC00UTf" +
            "kHvs7SLGEEwvNhRGlP7/KPG0ffNDNPrDaR2yHrB8UzJnFImB7k1S5ikbi7XdtFRIsaWasb" +
            "1sK3rC0fDN4/khm+OSfP4w574/vPu5Zxc2K85uD5+HJ79KhkfV/X+u9sb6K/HeuRG67Uys" +
            "ifVKUIzBW6RxRYvwgm1fC9ESCmqWepbNbTEr3cC3U+0Kc9ChkQIDAQABoxMwETAPBgNVHR" +
            "EECDAGhwTAqAEzMA0GCSqGSIb3DQEBCwUAA4ICAQAihvKBjWHnV7TeO5hf3Zm2gfc0Ktpy" +
            "tUkWtqiXSTrApwt5A2B0TQJ8wWHa28edRsIFrUzw1weXjy/DQcDQfJCVkZOvMlVT/02eYd" +
            "MHwcMmT/N5kfkdAbS2Vh6qje1AdCPp6NoY+wWmSrR6yAilx4EOxx/93/6fcRE1oCz1So6g" +
            "rxQXQCTTc1RQw6CD9ooJk0M7tjdNAx/oy1DBla567ET1DhY7Cus+emw7A0nKvUs1TVyOnL" +
            "txTFmMuu36zje4qhTkbxe9WJjaryhuHPc7sgjSNVHKuLSBl2N6GlPGiqcStFTrQAExbKQb" +
            "PPmwXjdx7i9Tp5EtEgFqakWh0+Ebc8+agUBTfk0DsSxIJCsidEG1o9Qg3eDemG/smpUfFl" +
            "t6DsfHgQ6qnmJTRI8JORACWrcchdbxut3OMpewitINNJlifOHWayC6yE89nrFxhDNA+E5Y" +
            "FOXkLtVTztlvRVWFh41veiqU6bk3zVfo2qedNadXI76Jj2QHL1GZZstr43E+gDsNwOvudn" +
            "QWAi/xGaPs31IW6L2krgmYeM377v0Mt3gcdfoKDKzHIzKdFPFxgZXO5AyI1uqeQthbktcf" +
            "ie7gMN76yZXqg+Cv4P+W/FVKtK/QHYremeTFt1xcgYP29n9hybNMipWVcLN3S+y7YtmVQN" +
            "hREffEln9qmgAsFoTgsWg0Fw==" );
=======
        directoryServers[0] = Base64.decode(            "MIIFKzCCAxOgAwIBAgIBATANBgkqhkiG9w0BAQsFADARMQ8wDQYDVQQDDAZpc3N1ZXIwHh" +
            "cNMTQwNDE2MTQwODMxWhcNMTQwNDMwMDc1NTA1WjCBizESMBAGA1UEAwwJMTAuMC4yLjE1" +
            "MQswCQYDVQQGEwJBVTEQMA4GA1UECgwHUGVlcmdvczESMBAGA1UEBwwJTWVsYm91cm5lMR" +
            "EwDwYDVQQIDAhWaWN0b3JpYTEvMC0GCSqGSIb3DQEJAQwgaGVsbG8uTlNBLkdDSFEuQVNJ" +
            "T0Bnb29kbHVjay5jb20wggIiMA0GCSqGSIb3DQEBAQUAA4ICDwAwggIKAoICAQCZxLhkh0" +
            "mXKKtlxgtzoFxquHVlpYwZ2vO8Ne6yaVRXHNVNlf7X045h/s1eCoZhsblZvF/qy5H8ANLH" +
            "0Dqih5T0F3OVs5YCwZD9z1ez2BmobprK0Va29XxgFCUh9m5FnFIfdKtuJVQ6yayjhUNKNg" +
            "FQQiFvPJbNV1UeJcSPt5fyNQJonJaUHaZppfVZleZYXR5mhKqvw/mZ00z02k+N8uhbqs22" +
            "FzMgwvoKidt6u9AmJxZAYDlYSH1DaBwCwkRpMEfOBo8QeVJF+QkcM7VWJErLCx91ehXKUr" +
            "XDrR9L9t8oE6ec7ZrZgCoM/Qz+uzbkwS4c51soZb9aVDZe90LBMII5wovxvbHOIhea8TpK" +
            "EHpzytn4b1W+Q68yKG/K+I5+EEqHRYATCZ/p51BhXMiMIx6Y8mooLSiie6JlfnLURHEmQT" +
            "Iu0+UR1LcaoKUvCaG5oTlEB0dAw6iGHDDI12d5DRrYgUYkW3d/fLZ/buthQ9FBjysOuHmC" +
            "+uIVKF5XU3PzHQgDck3JgRj49mKu19bwh0rbICPGXw787LRAtblq+/kjpdB6T/IOMe1lun" +
            "66dShgI9Fc7iLvrLxbH9XuZspZPA6gYPU1b8g8Gdt4HOPl9oiw8beeBqRtOKYXZYFI60dF" +
            "mcvd+TT8WRIJeIn1tSuaaYqTTtllv16xkRRRmueF9A62IQIDAQABoxMwETAPBgNVHREECD" +
            "AGhwQKAAIPMA0GCSqGSIb3DQEBCwUAA4ICAQAZl2hLLTZEZ4GUYkFXpa/CCB32/vKiN4OX" +
            "X6+mU+6HjVxPd+A4NsTqyzfUplu0107BYj5LEE+uegdXtnnaMoTJjiNA9WKBWKlRid42dc" +
            "LK0lPlARZncxe4lRWZ9h9ig35jrCwRXjPQcKf5V+ioP+D578zIpk/2uteBDKE2XAyKQkm5" +
            "gtgJuESpNBcZwzcyV+iKCd1wkTVfa8mhq0CRkF4oPwiRhwTkJzeU+JM/7OBc7Xs6r8IvNJ" +
            "siW8ckz/TxhnHfvD74b4U52FJ4Z6pm6nIC/gMsRPdhw1LnkoZ0S69A6Sj/4VgyDwEKb0IT" +
            "lNquAcR7dcgC0x+jpkcbzodxLTnO8QB3Wb0j8zAUSyCfrYnuv5IWlNSF4vpi3qmCyXnqMQ" +
            "pb4843hpggpSEyB/EQonv/kVA6X6fZmMzxZq9ojA9C2W3P50paFlhHNgcDZ0X85c1lou4d" +
            "FrFrlAXrLz6EfgaLQsjdUnGRxHhGz/P+vv/EkaeQDgvipbJ3kh3omWWt0L+a00g4XDv0Dt" +
            "jne8d7dOgjiIK7C47aELfywHJwJLLU4fCZbgkJ+eZj72pAhxAa4PC6WOLUFV2T9puLGn0q" +
            "MgPxJ3nE8Zg4IaMuMDPqgmYbkSnPckbDAFpDSkVqZ5SNSzIoHVy+cT+LMl1oq6y24k8ohG" +
            "lHyAO1lrF+aFbQcAMd2g==" );
>>>>>>> 96fa7beb9c093c6262fd40c4dc51f83c00270fbd
    }
}