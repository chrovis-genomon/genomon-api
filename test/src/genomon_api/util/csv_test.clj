(ns genomon-api.util.csv-test
  (:require [clojure.test :refer [deftest is are]]
            [genomon-api.util.csv :as csv]))

(deftest gen-dna-input-test
  (are [samples opts expected] (= expected (csv/gen-dna-input samples opts))
    {:tumor {:r1 "tr1.fastq" :r2 "tr2.fastq"}}
    {}
    "[fastq]
tumor,tr1.fastq,tr2.fastq

[mutation_call]
tumor,None,None

[sv_detection]
tumor,None,None"

    {:tumor {:r1 "tr1.fastq" :r2 "tr2.fastq"}}
    {:include-qc? true}
    "[fastq]
tumor,tr1.fastq,tr2.fastq

[mutation_call]
tumor,None,None

[sv_detection]
tumor,None,None

[qc]
tumor"

    {:tumor {:r1 "tr1.fastq" :r2 "tr2.fastq"}
     :normal {:r1 "nr1.fastq" :r2 "nr2.fastq"}}
    {}
    "[fastq]
tumor,tr1.fastq,tr2.fastq
normal,nr1.fastq,nr2.fastq

[mutation_call]
tumor,normal,None

[sv_detection]
tumor,normal,None"

    {:tumor {:r1 "tr1.fastq" :r2 "tr2.fastq"}
     :normal {:r1 "nr1.fastq" :r2 "nr2.fastq"}}
    {:include-qc? true}
    "[fastq]
tumor,tr1.fastq,tr2.fastq
normal,nr1.fastq,nr2.fastq

[mutation_call]
tumor,normal,None

[sv_detection]
tumor,normal,None

[qc]
tumor
normal"

    {:tumor {:r1 "tr1.fastq" :r2 "tr2.fastq"}
     :control-panel ["sample1.bam" "sample2.bam"]}
    {}
    "[fastq]
tumor,tr1.fastq,tr2.fastq

[bam_tofastq]
control_sample0,sample1.bam
control_sample1,sample2.bam

[controlpanel]
control_panel,control_sample0,control_sample1

[mutation_call]
tumor,None,None

[sv_detection]
tumor,None,control_panel"

    {:tumor {:r1 "tr1.fastq" :r2 "tr2.fastq"}
     :normal {:r1 "nr1.fastq" :r2 "nr2.fastq"}
     :control-panel ["sample1.bam" "sample2.bam"]}
    {}
    "[fastq]
tumor,tr1.fastq,tr2.fastq
normal,nr1.fastq,nr2.fastq

[bam_tofastq]
control_sample0,sample1.bam
control_sample1,sample2.bam

[controlpanel]
control_panel,control_sample0,control_sample1

[mutation_call]
tumor,normal,None

[sv_detection]
tumor,normal,control_panel"

    {:tumor {:bam "t.bam"}}
    {}
    "[bam_import]
tumor,t.bam

[mutation_call]
tumor,None,None

[sv_detection]
tumor,None,None"

    {:tumor {:bam "t.bam"},
     :normal {:bam "n.bam"}}
    {}
    "[bam_import]
normal,n.bam
tumor,t.bam

[mutation_call]
tumor,normal,None

[sv_detection]
tumor,normal,None"

    {:tumor {:r1 "tr1.fastq", :r2 "tr2.fastq"},
     :normal {:bam "n.bam"}}
    {}
    "[bam_import]
normal,n.bam

[fastq]
tumor,tr1.fastq,tr2.fastq

[mutation_call]
tumor,normal,None

[sv_detection]
tumor,normal,None"


    {:tumor {:bam "t.bam"},
     :normal {:r1 "nr1.fastq", :r2 "nr2.fastq"}}
    {}
    "[bam_import]
tumor,t.bam

[fastq]
normal,nr1.fastq,nr2.fastq

[mutation_call]
tumor,normal,None

[sv_detection]
tumor,normal,None")

  (is (thrown-with-msg?
       Throwable #"tumor is required"
       (csv/gen-dna-input {:normal {:bam "n.bam"}} {})))

  (is (thrown-with-msg?
       Throwable #"tumor is required"
       (csv/gen-dna-input {:tumor {:r1 "tr1.fastq"}} {})))

  (is (thrown-with-msg?
       Throwable #"Bam and fastq"
       (csv/gen-dna-input {:tumor {:bam "t.bam", :r1 "tr1.fastq"}} {})))

  (is (thrown-with-msg?
       Throwable #"Bam and fastq"
       (csv/gen-dna-input {:tumor {:bam "t.bam", :r2 "tr1.fastq"}} {}))))

(deftest gen-rna-input-test
  (are [samples opts expected] (= expected (csv/gen-rna-input samples opts))
    {:r1 "r1.fastq" :r2 "r2.fastq"}
    {}
    "[fastq]
rna,r1.fastq,r2.fastq

[sv_detection]
rna,None,None

[fusion]
rna,None

[expression]
rna

[intron_retention]
rna"

    {:r1 "r1.fastq" :r2 "r2.fastq"}
    {:include-qc? true}
    "[fastq]
rna,r1.fastq,r2.fastq

[sv_detection]
rna,None,None

[fusion]
rna,None

[expression]
rna

[intron_retention]
rna

[qc]
rna"

    {:r1 "r1.fastq" :r2 "r2.fastq" :control-panel ["sample1.bam"]}
    {}
    "[fastq]
rna,r1.fastq,r2.fastq

[bam_tofastq]
control_sample0,sample1.bam

[controlpanel]
control_panel,control_sample0

[sv_detection]
rna,None,control_panel

[fusion]
rna,control_panel

[expression]
rna

[intron_retention]
rna"

    {:r1 "r1.fastq" :r2 "r2.fastq" :control-panel ["sample1.bam"]}
    {:include-qc? true}
    "[fastq]
rna,r1.fastq,r2.fastq

[bam_tofastq]
control_sample0,sample1.bam

[controlpanel]
control_panel,control_sample0

[sv_detection]
rna,None,control_panel

[fusion]
rna,control_panel

[expression]
rna

[intron_retention]
rna

[qc]
rna"

    ))
