package com.example.objectdetection.customview;

import java.util.List;
import com.example.objectdetection.tflite.Classifier.Recognition;

public interface ResultsView {
  public void setResults(final List<Recognition> results);
}
