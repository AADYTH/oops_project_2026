package com.cropscanner.folix;

import java.util.Base64;

/**
 * Real (tiny, 64x48) camera-style fixtures generated with Pillow. The *_EXIF/_TEXT/_XMP variants carry
 * the marker string {@value #MARKER} (and phone make / GPS EXIF) so tests can prove metadata is removed
 * while the file still decodes.
 */
final class TestImages {

    static final String MARKER = "SECRET-LOCATION-MARKER";
    private TestImages() { }

    static byte[] jpeg_exif() {
        return Base64.getDecoder().decode(
                "/9j/4AAQSkZJRgABAQAAAQABAAD/4QDKRXhpZgAATU0AKgAAAAgAAwEOAAIAAAAXAAAAMgEPAAIAAAASAAAASoglAAQAAAABAAAA"
                + "XAAAAABTRUNSRVQtTE9DQVRJT04tTUFSS0VSAABTRUNSRVQtUEhPTkUtTUFLRQAABAABAAIAAAACTgAAAAACAAUAAAADAAAAkgAD"
                + "AAIAAAACRQAAAAAEAAUAAAADAAAAqgAAAAAAAAAMAAAAAQAAACIAAAABAAAAOAAAAAEAAABLAAAAAQAAAC4AAAABAAAADAAAAAH/"
                + "/gAXU0VDUkVULUNPTU1FTlQtTUFSS0VS/9sAQwAFAwQEBAMFBAQEBQUFBgcMCAcHBwcPCwsJDBEPEhIRDxERExYcFxMUGhURERgh"
                + "GBodHR8fHxMXIiQiHiQcHh8e/9sAQwEFBQUHBgcOCAgOHhQRFB4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4e"
                + "Hh4eHh4eHh4eHh4e/8AAEQgAMABAAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMC"
                + "BAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElK"
                + "U1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY"
                + "2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAEC"
                + "dwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFla"
                + "Y2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm"
                + "5+jp6vLz9PX29/j5+v/aAAwDAQACEQMRAD8A4iG1z2q5HZqBluBV+2twF3EcCrCW+fmYE+gr5+l7ONNSkrtn51RxTuUI7VOyk/hV"
                + "hLVQPuKD9c/pWglv6/8AfI/rU6W/oBj2/wAaU6kVfRfcv+H/ABv5Hs0MV5mfHajg7Af+A1YW2UqMKOvYf/rrQS35zgZ/nU6W/RcZ"
                + "5rnnVik1ZHs4fFeZnpagDJUfT/IqylqOMJ/46DV+O3HUcD1qdLfJ6YNc86sUv6/4f+vU9qhijn4bb92o9TVqO35zj2X/ABq7DbgI"
                + "p7gGrCW/bt0/xpLEJQWvRfl/nf52PxKhiSjHbenI/wDQqsR2544zzxj+lX0t+funnjGP0qdLckj5ePpWMq9v6/r/AIH5+zQxJQjt"
                + "zxgdasR2/Tjj6VfSAdanS3zjjP8A+quapiOzPaoYkopb/n9elTpbn07Zq+lvz0PX0qeODnJH04rnniOlz2aGJPGYdd1vaubzggf8"
                + "sk4/SrMeva53vef+uSc/pVaKAbANp6Y/z+dWI7f0B59v8+1fW2oKKtFfcj42hDD/AMi+5FmPXdczj7Zn/tkg/pU6a9reMG9HP/TJ"
                + "P8KrR2/+ycf5/wA/nVhIPY/pWE1R1XIvu/4B7VCGH/kX3Ispr2uA/wDH5/5CTOfyqePXdb3KPtn/AJCT/Cq8dueyAVPHbkMGZTiu"
                + "aao/yK3p/wAA9mhDD/yL7kWY9d1zHF4Mj/pkn+FWE17Wzj/Tf/ISf4VWSBT/AAmrCW+f4Sfw/wA/5+tc01Q2UU/kj2qEMP8AyL7k"
                + "f//Z");
    }

    static byte[] jpeg_progressive_exif() {
        return Base64.getDecoder().decode(
                "/9j/4AAQSkZJRgABAQAAAQABAAD/4QDKRXhpZgAATU0AKgAAAAgAAwEOAAIAAAAXAAAAMgEPAAIAAAASAAAASoglAAQAAAABAAAA"
                + "XAAAAABTRUNSRVQtTE9DQVRJT04tTUFSS0VSAABTRUNSRVQtUEhPTkUtTUFLRQAABAABAAIAAAACTgAAAAACAAUAAAADAAAAkgAD"
                + "AAIAAAACRQAAAAAEAAUAAAADAAAAqgAAAAAAAAAMAAAAAQAAACIAAAABAAAAOAAAAAEAAABLAAAAAQAAAC4AAAABAAAADAAAAAH/"
                + "2wBDAAUDBAQEAwUEBAQFBQUGBwwIBwcHBw8LCwkMEQ8SEhEPERETFhwXExQaFRERGCEYGh0dHx8fExciJCIeJBweHx7/2wBDAQUF"
                + "BQcGBw4ICA4eFBEUHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh7/wgARCAAwAEADASIA"
                + "AhEBAxEB/8QAGAABAQEBAQAAAAAAAAAAAAAAAwQCBQb/xAAaAQEBAAIDAAAAAAAAAAAAAAAEAwUGAAEC/9oADAMBAAIQAxAAAAHh"
                + "tQmP1ydKE6bOlG5snV0m3nqyc0kEo34YCOkmhujc2+MUky+GXZpNqIe5sVCSTf/EABsQAQADAQEBAQAAAAAAAAAAAAABAhIDERMh"
                + "/9oACAEBAAEFAoqiiKsoqyyyrVlllllFWUV/IqiqKoqirLKKsssvGWUQjv2R37o79337Pv3R37I79337IhFUVeIqirxl/8QAGBEA"
                + "AwEBAAAAAAAAAAAAAAAAAAIDAVH/2gAIAQMBAT8BSolSdRKiUEoJQSgmT4Jk+CZPgmT4f//EAB4RAAICAgIDAAAAAAAAAAAAAAAB"
                + "AhIDURETISIx/9oACAECAQE/AY1UeWOSHJDkjs8DmSyDyHpx8HTQ6aHTR//EABgQAAMBAQAAAAAAAAAAAAAAAAAyoTBQ/9oACAEB"
                + "AAY/AuA8Hg8Hg8Hg8HmP/8QAIhAAAgEDBAIDAAAAAAAAAAAAAAFhESHxEDFBUaHBIOHw/9oACAEBAAE/ITRyJ6EvSIfAkpsKIo+C"
                + "xUXJiwEEFw0ELQ+g/HfwAQQtIQUBQEUFoaVegwgwAWMFiCpXoMAFjCztoQCMBcq0JehQP//aAAwDAQACAAMAAAAQx6HrM1LWsSlP"
                + "/8QAGhEAAgMBAQAAAAAAAAAAAAAAAAFRYZERIP/aAAgBAwEBPxBnS4s9X/G4iNxEbiI3Ef/EACQRAQACAAMIAwAAAAAAAAAAAAEA"
                + "ESGR8DFBUXGBseHxYaHR/9oACAECAQE/EKgLWEvAyPf3fxDCUQxr3rnABx3Hb9vrUp1rx34DN1coihkQ4iuXiHg1y8Q7IHoT/8QA"
                + "JRAAAQMCBgIDAQAAAAAAAAAAAQARITFBUWFxkaHwgcGx0fHh/9oACAEBAAE/EHLIAPAKxI+EIKR1fhAgtKFAwVsP1CAchp0IUNxF"
                + "CDggJ6AnALG2e1hANl9qZ2D/ACqTHlAqIGKeNGKhMSpHbL7LBkISIeYb0iQwqqERot7WiNhZ0AGuAVatT7U1UwzcIxIs0Q6p5of8"
                + "U1DXBSuRpClkCBl8K4nv8J00vH6TViM96IQ/1vsmZ1+EygHHeiNbv2Q2h1G7usAGcu5LMN3u6yzwiWEIgAhkdcnrj473Vf/Z");
    }

    static byte[] jpeg_plain() {
        return Base64.getDecoder().decode(
                "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAUDBAQEAwUEBAQFBQUGBwwIBwcHBw8LCwkMEQ8SEhEPERETFhwXExQaFRERGCEYGh0d"
                + "Hx8fExciJCIeJBweHx7/2wBDAQUFBQcGBw4ICA4eFBEUHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4e"
                + "Hh4eHh4eHh7/wAARCAAwAEADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF"
                + "BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW"
                + "V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi"
                + "4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC"
                + "AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm"
                + "Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq"
                + "8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDiIbXParkdmoGW4FX7a3AXcRwKsJb5+ZgT6Cvn6Xs401KSu2fnVHFO5QjtU7KT+FWEtVA+"
                + "4oP1z+laCW/r/wB8j+tTpb+gGPb/ABpTqRV9F9y/4f8AG/kezQxXmZ8dqODsB/4DVhbZSowo69h/+utBLfnOBn+dTpb9FxnmuedW"
                + "KTVkezh8V5melqAMlR9P8irKWo4wn/joNX47cdRwPWp0t8npg1zzqxS/r/h/69T2qGKOfhtv3aj1NWo7fnOPZf8AGrsNuAinuAas"
                + "Jb9u3T/GksQlBa9F+X+d/nY/EqGJKMdt6cj/ANCqxHbnjjPPGP6VfS35+6eeMY/Sp0tySPl4+lYyr2/r+v8Agfn7NDElCO3PGB1q"
                + "xHb9OOPpV9IB1qdLfOOM/wD6q5qmI7M9qhiSilv+f16VOlufTtmr6W/PQ9fSp44OckfTiueeI6XPZoYk8Zh13W9q5vOCB/yyTj9K"
                + "sx69rne95/65Jz+lVooBsA2npj/P51Yjt/QHn2/z7V9bagoq0V9yPjaEMP8AyL7kWY9d1zOPtmf+2SD+lTpr2t4wb0c/9Mk/wqtH"
                + "b/7Jx/n/AD+dWEg9j+lYTVHVci+7/gHtUIYf+Rfciymva4D/AMfn/kJM5/Kp49d1vco+2f8AkJP8Krx257IBU8duQwZlOK5pqj/I"
                + "ren/AAD2aEMP/IvuRZj13XMcXgyP+mSf4VYTXtbOP9N/8hJ/hVZIFP8ACasJb5/hJ/D/AD/n61zTVDZRT+SPaoQw/wDIvuR//9k=");
    }

    static byte[] png_text_exif() {
        return Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAwCAIAAAAuKetIAAAAH3RFWHRMb2NhdGlvbgBTRUNSRVQtTE9DQVRJT04tTUFSS0VSEsTt"
                + "dwAAAMJlWElmTU0AKgAAAAgAAwEOAAIAAAAXAAAAMgEPAAIAAAASAAAASoglAAQAAAABAAAAXAAAAABTRUNSRVQtTE9DQVRJT04t"
                + "TUFSS0VSAABTRUNSRVQtUEhPTkUtTUFLRQAABAABAAIAAAACTgAAAAACAAUAAAADAAAAkgADAAIAAAACRQAAAAAEAAUAAAADAAAA"
                + "qgAAAAAAAAAMAAAAAQAAACIAAAABAAAAOAAAAAEAAABLAAAAAQAAAC4AAAABAAAADAAAAAEpXIEnAAAIxUlEQVR4nNXYWXOUR5bG"
                + "8R+8YBuBwMiAkITKWkqqUi1d2nfkrzXfZm67bbd305hVQkL73tKsPTM9zDiiu21ss2/GzIUiifelqoTAREx0xHPxP09mKXSU55xM"
                + "1b7UP2T38/erAyLs+/vV8wT+3/+Uv329D8YTiN60fvcqm4uv95NfSODA6+rDSmbqV/+Eahp/zuUJHNybPkqG9a/1qbiKr/WpgxUT"
                + "eKu6Pg5Qt+u2FzY/V/pVNj/X4C6bqyXwdpl+z9scq7RUvm1Hqb1t21Fxb9t2NP6cd0ngnZg+oTbpvFO2YQcaX7ZhR5mXbdhR/0v3"
                + "7J7AIT7lEEc4VEmfBqjfdfUQbbuuHqK46+qORspXd0/gU2qq6DNqOFF9qYZU9aUastWXauitvlTD+POll55ADYfL9Dl1VfzDNFXx"
                + "D5Ou4h+mWH1psIp/mA92SeATjoQEjgR9wRGOx5y431DJPEJrFb+rkrmj3ir+6AtmtQR+T23sBI5Qy5ccozapL6mlvpKZqmTW0lnJ"
                + "LFYyaxmoZI4/N/dYQkf4kqNl+oqTZc5RzlQy28uco+TKnKP0VDKHy5yj1UroY44lE/iCYxzlWNDXHONEmdNY5hyjtczJlDnFMucY"
                + "/WXO6AvO3k+gNiTwLueo492YznG6zEklw3dJlzm5ZPgu3WXOYJkzHsK7e0ngc47HEjjH8aT+QH0yPE5zMmwr25BNhsVkeJy+ZHic"
                + "keDc5g4THC9P4CPqYgl8Rl3sBL6mjuPUUcd56jiVDJuSYUsyrKMzGeaTYXcyHEiGtxjjPHe5x/3XK6GdBL7hBO8FfUNDMkwlw/YY"
                + "v0c2GRaTYW8yHAq887cfD+GDl97EJ2IJfMWJkMB5TsR0gdMxPkFzMmyLcWdyKR/jE3QnwwEu8BO3GAlL97jPBBdeaYyeTJ7Ae5zg"
                + "JBep52TgphifpCUWppNLXTEuJpd6A//IYOA73GWMizzg4e438S4l9AdOhQQuciroEo0xTsX4FO0xzsQ4H+NTdIfwB/oD32Y48H3G"
                + "Az9+IYEPqY89Jepj90B9bArVhxO4QD2nqOcyDdQHbo5xa4w7YtwV42KA77lJL5dD5QxyOXTtKJd5yCMmuPyqJ/A1p2NNfJqTnOIy"
                + "p4OucCbAaVpinI5xNsYFrvAdpWD+SF/gOwwFfsAYV3jMkz2W0Oc0lE2h8zSEHrhEA6dp4CpNAVI0BG4L0EBnjHOBiwF+oCfwbQa4"
                + "Grp2hKs8YjxseLrLY66x7AS+orGsiS/SSD2nuUojjUzSHOPWAOmYmQ3wNwpMcpNSMG/RxyR3GQzmQ0aZ5Ak/M8HkS0/gM5oqNXFT"
                + "6IGm0ANXaKKRKc7QxBRNtARuj5kZpvgruWAWA/xED1PcoT+YDxhmiseMMcVTfnmlKfQlZ6qM0Z0eOEMDk5zhDNdIxbgtQEeAbIDv"
                + "KXCNHykFszfAfQa5xiNGuMbPjIdVrzFGz9Ecmrg59EBzKKFmmrhGc9A0rQHSATIBcgGKAW7TwzT36GeahwwxzRNGmeYXnjHBdDyB"
                + "35EKz+lUuIlTYYymwhRKhSZOhR5IhRJKhRJK0cwMKVqY4VvameEvdDLDd3Qxww8UmOEWJWa4Sy8zPGCAGR4zzAxPGWOGnV/721/z"
                + "FoqXUH2YQo00cYZmUrzPdVoDpANkAuQCFAN0B+gLMBhgJMA419n/a27iltDELaEHWkIPtHCGaVq4TgstzNIeoDNAV4ACs9yhxCz3"
                + "6WWWRwwwy88MM8szxpjlLLPVvhstvwdawxhtrXQCl2gNPdAaeqCVGVppYY42WpmjI0CWOX4izxzF4HcH6GOOJwwyxy+MMMc+bhAx"
                + "wdwB/7jLtxJtVZq4LTRxW6yErtAWeqCNZr7lfWZpo4150gEyzNMVwgLz3KPEPA/pZZ5+5nnKEPNglHnGmefgq47R9uRN3B6auD3W"
                + "A5O0hxJq5zrttDJPOwvcpJMFssHJs0AxhN0s8Jg+FhhggWcMs8B+xljgLAsVvxv9iHTyIksn30LpWAldIB16IM1V0qGE0syQDiWU"
                + "ZoE0aRbJsMhtcixSYJEHlFikh0V+pp9FBllkHyMscoAbvMUEi29kCl2iIzaFpuigmb+EEuqgjZukWaKTJbJ0sESeJYoh7GaJ3hAO"
                + "sMQQS0SMssQ4S7yz9xL6gs7kFOrkGzqTPdAZG6PTdHKdzlBCnSzSyTKZADmWKbDMI0os08Myv9DPMoMsM8wyBxljmbMsU7PLYy5T"
                + "6TGXqfSYyyR7IMM1MsyQCSWUYYEMS2TIsEIXK+RZoRjMblboZQUMsMIQK4ywwtvc4BATrLz0f+JspSmU5TzZ5Fsoy1WyTJGlme9C"
                + "CWWZJ8siWTpZIcsqOVYpsMoTSqzSwyp9rLKfP3GAYVYZZZVxVjm8x5v4c7qq9MAFurhEV6wH/hpKqIvrdDFHF+3cpoNlulilizXy"
                + "rFEMYTdr9LJGP2sMssZbjLDGGGucZe1Vx2iOr8lVeUpcIcckOa6RY4YcLfxEGwvkWCLHCjnWyLFOgXWeUmKdHtbpY50B1hlinXf4"
                + "MzXc4AgTrL/2UyLPN+S5SD7WA3+jie9p5odQQnnmybNInmXyrJJnnTwbFAN0s0EvG/SzwSAbDLPBKBuMs8HRN/iYu0yBqxSYosA0"
                + "Ba5TYI4CCxRYosAKBdYosEGBTZ5RYpMeNuljkwE2GWKTETYZY5OzbPLBfhHRPtFvRZHoQ9FB0ceit0WfiA6JPhMdFn0hqhV9JTom"
                + "Oic6Ljovek90QXRSdElUL7oiahBNippE10TNohnR+6JZUatoXtQuWhR1iJZFGdGqqEu0LsqLNkVF0ZZoW/RPon8W/YvoX0X/Jvp3"
                + "0Z9E/yH6T9F/if4s+m/RDdH/iP5X9Caf09/RwE2a+JFmbvE+d2jlHu08oINHZHhCF0/J84w/8hu26GaLXrboZ4tBthhmi1G2GGeL"
                + "CbbeYAmVuEqJKUpMU+I6JeYosUCJJUqsUGKNEhuU2McWJbbpYZs+thlgmyG2GWGbMbY5yzYf/B+B8t1T7XxnHgAAAABJRU5ErkJg"
                + "gg==");
    }

    static byte[] png_plain() {
        return Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAwCAIAAAAuKetIAAAIxUlEQVR4nNXYWXOUR5bG8R+8YBuBwMiAkITKWkqqUi1d2nfkrzXf"
                + "Zm67bbd305hVQkL73tKsPTM9zDiiu21ss2/GzIUiifelqoTAREx0xHPxP09mKXSU55xM1b7UP2T38/erAyLs+/vV8wT+3/+Uv329"
                + "D8YTiN60fvcqm4uv95NfSODA6+rDSmbqV/+Eahp/zuUJHNybPkqG9a/1qbiKr/WpgxUTeKu6Pg5Qt+u2FzY/V/pVNj/X4C6bqyXw"
                + "dpl+z9scq7RUvm1Hqb1t21Fxb9t2NP6cd0ngnZg+oTbpvFO2YQcaX7ZhR5mXbdhR/0v37J7AIT7lEEc4VEmfBqjfdfUQbbuuHqK4"
                + "6+qORspXd0/gU2qq6DNqOFF9qYZU9aUastWXauitvlTD+POll55ADYfL9Dl1VfzDNFXxD5Ou4h+mWH1psIp/mA92SeATjoQEjgR9"
                + "wRGOx5y431DJPEJrFb+rkrmj3ir+6AtmtQR+T23sBI5Qy5ccozapL6mlvpKZqmTW0lnJLFYyaxmoZI4/N/dYQkf4kqNl+oqTZc5R"
                + "zlQy28uco+TKnKP0VDKHy5yj1UroY44lE/iCYxzlWNDXHONEmdNY5hyjtczJlDnFMucY/WXO6AvO3k+gNiTwLueo492YznG6zEkl"
                + "w3dJlzm5ZPgu3WXOYJkzHsK7e0ngc47HEjjH8aT+QH0yPE5zMmwr25BNhsVkeJy+ZHickeDc5g4THC9P4CPqYgl8Rl3sBL6mjuPU"
                + "Ucd56jiVDJuSYUsyrKMzGeaTYXcyHEiGtxjjPHe5x/3XK6GdBL7hBO8FfUNDMkwlw/YYv0c2GRaTYW8yHAq887cfD+GDl97EJ2IJ"
                + "fMWJkMB5TsR0gdMxPkFzMmyLcWdyKR/jE3QnwwEu8BO3GAlL97jPBBdeaYyeTJ7Ae5zgJBep52TgphifpCUWppNLXTEuJpd6A//I"
                + "YOA73GWMizzg4e438S4l9AdOhQQuciroEo0xTsX4FO0xzsQ4H+NTdIfwB/oD32Y48H3GAz9+IYEPqY89Jepj90B9bArVhxO4QD2n"
                + "qOcyDdQHbo5xa4w7YtwV42KA77lJL5dD5QxyOXTtKJd5yCMmuPyqJ/A1p2NNfJqTnOIyp4OucCbAaVpinI5xNsYFrvAdpWD+SF/g"
                + "OwwFfsAYV3jMkz2W0Oc0lE2h8zSEHrhEA6dp4CpNAVI0BG4L0EBnjHOBiwF+oCfwbQa4Grp2hKs8YjxseLrLY66x7AS+orGsiS/S"
                + "SD2nuUojjUzSHOPWAOmYmQ3wNwpMcpNSMG/RxyR3GQzmQ0aZ5Ak/M8HkS0/gM5oqNXFT6IGm0ANXaKKRKc7QxBRNtARuj5kZpvgr"
                + "uWAWA/xED1PcoT+YDxhmiseMMcVTfnmlKfQlZ6qM0Z0eOEMDk5zhDNdIxbgtQEeAbIDvKXCNHykFszfAfQa5xiNGuMbPjIdVrzFG"
                + "z9Ecmrg59EBzKKFmmrhGc9A0rQHSATIBcgGKAW7TwzT36GeahwwxzRNGmeYXnjHBdDyB35EKz+lUuIlTYYymwhRKhSZOhR5IhRJK"
                + "hRJK0cwMKVqY4VvameEvdDLDd3Qxww8UmOEWJWa4Sy8zPGCAGR4zzAxPGWOGnV/721/zFoqXUH2YQo00cYZmUrzPdVoDpANkAuQC"
                + "FAN0B+gLMBhgJMA419n/a27iltDELaEHWkIPtHCGaVq4TgstzNIeoDNAV4ACs9yhxCz36WWWRwwwy88MM8szxpjlLLPVvhstvwda"
                + "wxhtrXQCl2gNPdAaeqCVGVppYY42WpmjI0CWOX4izxzF4HcH6GOOJwwyxy+MMMc+bhAxwdwB/7jLtxJtVZq4LTRxW6yErtAWeqCN"
                + "Zr7lfWZpo4150gEyzNMVwgLz3KPEPA/pZZ5+5nnKEPNglHnGmefgq47R9uRN3B6auD3WA5O0hxJq5zrttDJPOwvcpJMFssHJs0Ax"
                + "hN0s8Jg+FhhggWcMs8B+xljgLAsVvxv9iHTyIksn30LpWAldIB16IM1V0qGE0syQDiWUZoE0aRbJsMhtcixSYJEHlFikh0V+pp9F"
                + "BllkHyMscoAbvMUEi29kCl2iIzaFpuigmb+EEuqgjZukWaKTJbJ0sESeJYoh7GaJ3hAOsMQQS0SMssQ4S7yz9xL6gs7kFOrkGzqT"
                + "PdAZG6PTdHKdzlBCnSzSyTKZADmWKbDMI0os08Myv9DPMoMsM8wyBxljmbMsU7PLYy5T6TGXqfSYyyR7IMM1MsyQCSWUYYEMS2TI"
                + "sEIXK+RZoRjMblboZQUMsMIQK4ywwtvc4BATrLz0f+JspSmU5TzZ5Fsoy1WyTJGlme9CCWWZJ8siWTpZIcsqOVYpsMoTSqzSwyp9"
                + "rLKfP3GAYVYZZZVxVjm8x5v4c7qq9MAFurhEV6wH/hpKqIvrdDFHF+3cpoNlulilizXyrFEMYTdr9LJGP2sMssZbjLDGGGucZe1V"
                + "x2iOr8lVeUpcIcckOa6RY4YcLfxEGwvkWCLHCjnWyLFOgXWeUmKdHtbpY50B1hlinXf4MzXc4AgTrL/2UyLPN+S5SD7WA3+jie9p"
                + "5odQQnnmybNInmXyrJJnnTwbFAN0s0EvG/SzwSAbDLPBKBuMs8HRN/iYu0yBqxSYosA0Ba5TYI4CCxRYosAKBdYosEGBTZ5RYpMe"
                + "NuljkwE2GWKTETYZY5OzbPLBfhHRPtFvRZHoQ9FB0ceit0WfiA6JPhMdFn0hqhV9JTomOic6Ljovek90QXRSdElUL7oiahBNippE"
                + "10TNohnR+6JZUatoXtQuWhR1iJZFGdGqqEu0LsqLNkVF0ZZoW/RPon8W/YvoX0X/Jvp30Z9E/yH6T9F/if4s+m/RDdH/iP5X9Caf"
                + "09/RwE2a+JFmbvE+d2jlHu08oINHZHhCF0/J84w/8hu26GaLXrboZ4tBthhmi1G2GGeLCbbeYAmVuEqJKUpMU+I6JeYosUCJJUqs"
                + "UGKNEhuU2McWJbbpYZs+thlgmyG2GWGbMbY5yzYf/B+B8t1T7XxnHgAAAABJRU5ErkJggg==");
    }

    static byte[] webp_exif_xmp() {
        return Base64.getDecoder().decode(
                "UklGRgoDAABXRUJQVlA4WAoAAAAMAAAAPwAALwAAVlA4IPwBAADQDACdASpAADAAPm0ulUckIiIhLVZpIIANiWQ9HKU/2zHx3/df"
                + "69AKs2+Wt/r3mgVtrpBMoW+4C57nTO+i29VD/TvmkdKgj+1IJXHNYERyR2WMb1YKcV8YWtDczEnYyi4MwjPDoEIc0oe6EsvdhoAA"
                + "/uvvG+ek07N4D9FyOX5wCvokumSP/+J/pS8WpFVIXTfX++9ihjaz0zsmpNxs9H/Fc72jl8lJtjKg5CF3s2me6a6+uK8FTiz7J03K"
                + "ENvug0/Z6gLqnzMsb35SMxKDdk5LOsDK8kckiAaCuqFHSPVEQzuxGGPuljYtIGn/ARXNYHmjz1xB+V7HD7aRwJ2DXCHU/FPfrJFG"
                + "+wV1RUCXJ78IODvQMBnSXEXYJYr+S3Bdfh1pjFE+FhtICBgQSaQhwWDFwPo0OoBPMWH3swjB6A6m5aUK8AMHrPWV6HaVf1+CXoQ7"
                + "97+MPGz3UhN5GxKZNH88rIbeGBotJymfhOo0JDlxTObBIyTUI/0Sd/a4V3vEtcIz5dlD9VuRXD0nDGfXqZXbsOluLQL6ta/jOEdn"
                + "2AVRqzXLvkbz/BSG006w2oTqm3K3IdbGGSyayEkiDy8m4amkDP25S8STwHsNumXo7RoGtwtRMI8KbmvJmiNPXxJvRQBQa+oDpTl1"
                + "b+K4JYGgRPlZUTA2cJHqAGoAAAAARVhJRsIAAABNTQAqAAAACAADAQ4AAgAAABcAAAAyAQ8AAgAAABIAAABKiCUABAAAAAEAAABc"
                + "AAAAAFNFQ1JFVC1MT0NBVElPTi1NQVJLRVIAAFNFQ1JFVC1QSE9ORS1NQUtFAAAEAAEAAgAAAAJOAAAAAAIABQAAAAMAAACSAAMA"
                + "AgAAAAJFAAAAAAQABQAAAAMAAACqAAAAAAAAAAwAAAABAAAAIgAAAAEAAAA4AAAAAQAAAEsAAAABAAAALgAAAAEAAAAMAAAAAVhN"
                + "UCAdAAAAPHg+U0VDUkVULUxPQ0FUSU9OLU1BUktFUjwveD4A");
    }

    static byte[] webp_plain() {
        return Base64.getDecoder().decode(
                "UklGRggCAABXRUJQVlA4IPwBAADQDACdASpAADAAPm0ulUckIiIhLVZpIIANiWQ9HKU/2zHx3/df69AKs2+Wt/r3mgVtrpBMoW+4"
                + "C57nTO+i29VD/TvmkdKgj+1IJXHNYERyR2WMb1YKcV8YWtDczEnYyi4MwjPDoEIc0oe6EsvdhoAA/uvvG+ek07N4D9FyOX5wCvok"
                + "umSP/+J/pS8WpFVIXTfX++9ihjaz0zsmpNxs9H/Fc72jl8lJtjKg5CF3s2me6a6+uK8FTiz7J03KENvug0/Z6gLqnzMsb35SMxKD"
                + "dk5LOsDK8kckiAaCuqFHSPVEQzuxGGPuljYtIGn/ARXNYHmjz1xB+V7HD7aRwJ2DXCHU/FPfrJFG+wV1RUCXJ78IODvQMBnSXEXY"
                + "JYr+S3Bdfh1pjFE+FhtICBgQSaQhwWDFwPo0OoBPMWH3swjB6A6m5aUK8AMHrPWV6HaVf1+CXoQ797+MPGz3UhN5GxKZNH88rIbe"
                + "GBotJymfhOo0JDlxTObBIyTUI/0Sd/a4V3vEtcIz5dlD9VuRXD0nDGfXqZXbsOluLQL6ta/jOEdn2AVRqzXLvkbz/BSG006w2oTq"
                + "m3K3IdbGGSyayEkiDy8m4amkDP25S8STwHsNumXo7RoGtwtRMI8KbmvJmiNPXxJvRQBQa+oDpTl1b+K4JYGgRPlZUTA2cJHqAGoA"
                + "AAAA");
    }

}
